package com.example.sqlquery.infra;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按数据源的<b>熔断器</b>。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>用户填的数据源，<b>它的可用性不由我们控制</b>：那台机器可能关机了、端口被防火墙挡了、
 * 数据库正在重启。这些情况下每一次查询都要走完
 * "建连超时（{@code connectTimeout} 5 秒）→ 失败"这条路。
 *
 * <p>而连接池只有 5 个连接、还有 5 秒的建连超时：<b>5 个并发请求就能把这个池子占满 5 秒</b>，
 * 期间后面所有的请求（包括<b>别的数据源</b>的吗？不是 —— 池子是按数据源分的，
 * 但 Web 线程是共用的）都在排队。一个连不上的库，能把整个平台拖慢。
 *
 * <p>熔断之后，连续失败达到阈值就<b>直接拒绝</b>，不再去尝试连接。
 * 过一段时间放<b>一个</b>请求去试探，成功了就恢复。
 *
 * <h2>⭐⭐ 最关键的设计点：什么算"失败"</h2>
 *
 * <p><b>不能把所有 SQLException 都算进去。</b>用户写错 SQL（列不存在、语法错误）
 * 也会抛 SQLException —— 如果把它算成失败，那么<b>用户连续写错 5 次 SQL，
 * 这个数据源就被熔断了</b>，别人也用不了。这是一个非常荒谬但很容易踩到的坑。
 *
 * <table border="1">
 *   <caption>两类失败</caption>
 *   <tr><th>类型</th><th>例子</th><th>算熔断失败吗</th><th>为什么</th></tr>
 *   <tr><td><b>连接类</b></td><td>连不上、建连超时、池子拿不到连接</td><td>✅ 算</td>
 *       <td>这是<b>数据源本身</b>的问题，重试没有意义，而且很慢</td></tr>
 *   <tr><td><b>业务类</b></td><td>Unknown column、语法错误、权限不足</td><td>❌ 不算</td>
 *       <td>这是<b>这一次请求</b>的问题。算进去会让"别人写错 SQL"影响到所有用户</td></tr>
 * </table>
 *
 * <h2>⚠️ 已知边界</h2>
 *
 * <ul>
 *   <li><b>JVM 内</b>，多实例各判各的 —— 和连接池一致（池子也是每实例一份），所以是对齐的；</li>
 *   <li>阈值的取值<b>没有实测依据</b>，是按"5 个连接 × 一次失败"估的，这一点在文档里如实标注；</li>
 *   <li>半开状态只放<b>一个</b>请求进去探测，如果这个探测请求恰好因为别的原因失败，
 *       会重新熔断 —— 代价是多等一个周期，可以接受。</li>
 * </ul>
 */
@Slf4j
@Component
public class DbSourceCircuitBreaker {

    /** 熔断器的三种状态。 */
    private enum State {
        /** 正常放行。 */
        CLOSED,
        /** 熔断中，直接拒绝。 */
        OPEN,
        /** 试探中：放一个请求进去看能不能恢复。 */
        HALF_OPEN
    }

    private static final class Breaker {
        State state = State.CLOSED;
        int consecutiveFailures = 0;
        long openedAt = 0;
    }

    private final int failureThreshold;
    private final long openMillis;
    private final SqlPlatformMetrics metrics;

    private final ConcurrentHashMap<Long, Breaker> breakers = new ConcurrentHashMap<>();

    public DbSourceCircuitBreaker(
            @Value("${platform.query-load.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${platform.query-load.circuit-breaker.open-seconds:30}") int openSeconds,
            SqlPlatformMetrics metrics) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1, openSeconds) * 1000L;
        this.metrics = metrics;
    }

    /**
     * 这个数据源现在能不能打。
     *
     * @return {@code false} 表示熔断打开，调用方应该<b>立刻</b>回一句"该数据源当前不可用"
     */
    public boolean allowRequest(Long dataSourceId) {
        Breaker breaker = breakers.get(dataSourceId);
        if (breaker == null) {
            return true;
        }

        synchronized (breaker) {
            if (breaker.state == State.CLOSED) {
                return true;
            }
            if (breaker.state == State.OPEN) {
                if (System.currentTimeMillis() - breaker.openedAt >= openMillis) {
                    // 冷却时间到了：转半开，放这一次请求进去试探
                    breaker.state = State.HALF_OPEN;
                    log.info("数据源熔断器进入半开状态，放一个请求探测。dataSourceId={}", dataSourceId);
                    return true;
                }
                metrics.recordCircuitRejected();
                return false;
            }
            // HALF_OPEN：已经有别的请求在探测了，其它的一律拒绝
            metrics.recordCircuitRejected();
            return false;
        }
    }

    /** 这一次打成功了。半开状态下就恢复；闭合状态下清零连续失败计数。 */
    public void recordSuccess(Long dataSourceId) {
        Breaker breaker = breakers.get(dataSourceId);
        if (breaker == null) {
            return;
        }
        synchronized (breaker) {
            if (breaker.state == State.HALF_OPEN) {
                // 计数：只有 opened 没有 recovered 的话，看板上分不清"已恢复"和"还在熔断中"
                metrics.recordCircuitRecovered();
                log.info("数据源探测成功，熔断器恢复。dataSourceId={}", dataSourceId);
                breaker.state = State.CLOSED;
                breaker.consecutiveFailures = 0;
                breakers.remove(dataSourceId, breaker);
                return;
            }
            breaker.consecutiveFailures = 0;
        }
    }

    /**
     * 记录失败，**并告诉调用方这算不算基础设施失败**。
     *
     * <p>为什么需要它（第六轮补）：{@code QueryServiceImpl} 的 catch 以前只接 {@code BusinessException}，
     * 而**连接池创建失败**（Hikari 的 {@code PoolInitializationException}）是个 {@code RuntimeException} ——
     * 它会直接穿到全局兜底，于是熔断器**完全不知道这个数据源连不上**：
     * 每次请求都要重新等满建连超时，熔断永远不打开。
     *
     * <p>调用方拿到 {@code true} 之后可以做两件事：记一次失败（本方法已经做了）、
     * 并且给用户一句**说得通的话**（而不是"系统异常"）。
     *
     * @return 这次失败是否属于"数据源本身的问题"
     */
    public boolean recordFailureAndClassify(Long dataSourceId, Throwable error) {
        if (!isInfrastructureFailure(error)) {
            return false;
        }
        recordFailure(dataSourceId, error);
        return true;
    }

    /**
     * 这一次打失败了。<b>只有"连接类"失败才计入熔断</b> —— 见类注释里那张表。
     */
    public void recordFailure(Long dataSourceId, Throwable error) {
        if (!isInfrastructureFailure(error)) {
            return;
        }

        Breaker breaker = breakers.computeIfAbsent(dataSourceId, k -> new Breaker());
        synchronized (breaker) {
            if (breaker.state == State.HALF_OPEN) {
                // 探测也失败 → 重新熔断，重新开始计时
                breaker.state = State.OPEN;
                breaker.openedAt = System.currentTimeMillis();
                log.warn("数据源探测失败，继续熔断 {} 秒。dataSourceId={}", openMillis / 1000, dataSourceId);
                return;
            }

            breaker.consecutiveFailures++;
            if (breaker.state == State.CLOSED && breaker.consecutiveFailures >= failureThreshold) {
                breaker.state = State.OPEN;
                breaker.openedAt = System.currentTimeMillis();
                metrics.recordCircuitOpened();
                log.warn("数据源连续失败 {} 次，熔断 {} 秒，其间不再尝试连接。dataSourceId={}, 最后一次错误={}",
                        breaker.consecutiveFailures, openMillis / 1000, dataSourceId, brief(error));
            }
        }
    }

    /**
     * 判断这是不是"数据源本身有问题"。
     *
     * <p>沿着 {@code cause} 链一路找，因为异常经常被包好几层
     * （{@code BusinessException} → {@code SQLException} → {@code ConnectException}）。
     *
     * <p><b>刻意不把 {@code SQLException} 本身算进来</b>：它是所有数据库错误的父类，
     * 把父类算进来等于"所有 SQL 错误都熔断"，那正是上面说的那个荒谬后果。
     * 只认真列举出来的几种。
     */
    private boolean isInfrastructureFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {   // 防御异常链成环
            if (current instanceof SQLTransientConnectionException
                    || current instanceof SQLTimeoutException
                    || current instanceof ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.UnknownHostException) {
                return true;
            }
            String className = current.getClass().getName();
            // HikariCP 在池子初始化失败时抛的是自己的异常，不在 java.sql 体系里
            if (className.startsWith("com.zaxxer.hikari.pool.")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String brief(Throwable error) {
        if (error == null) {
            return "未知";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    /** 仅供测试：读当前状态名。 */
    String stateOf(Long dataSourceId) {
        Breaker breaker = breakers.get(dataSourceId);
        return breaker == null ? "CLOSED" : breaker.state.name();
    }
}
