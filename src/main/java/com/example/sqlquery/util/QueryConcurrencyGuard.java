package com.example.sqlquery.util;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 查询的**并发**闸门（不是速率限制）。
 *
 * <h2>为什么已经有 {@link RateLimitUtil} 了还需要这个</h2>
 *
 * <p>两个东西回答的是完全不同的问题：
 *
 * <table border="1">
 *   <caption>速率限制 vs 并发限制</caption>
 *   <tr><th></th><th>速率限制（RateLimitUtil）</th><th>并发限制（本类）</th></tr>
 *   <tr><td>约束的是</td><td>单位时间内的<b>次数</b></td><td>同一时刻在处理的<b>请求数</b></td></tr>
 *   <tr><td>拦得住</td><td>脚本刷接口</td><td><b>开 20 个标签页同时点</b></td></tr>
 *   <tr><td>用什么实现</td><td>Redis 计数器 / ZSet</td><td>JVM 内的 {@link Semaphore}</td></tr>
 * </table>
 *
 * <p>⚠️ <b>"每分钟 60 次"完全挡不住瞬时并发</b>：60 次可以在一秒内全部打出去，
 * 而那时候它们会同时去抢数据库连接 —— 而每个业务数据源的连接池<b>只有 5 个连接</b>。
 *
 * <h2>它保护的是哪个资源</h2>
 *
 * <p>连接池。这是全项目<b>最稀缺</b>的资源：{@code ConnectionManager.MAX_POOL_SIZE = 5}，
 * 而且查询、导出、工单执行<b>共用</b>同一个池子。
 *
 * <p>没有闸门时会发生什么：第 6 个请求在 {@code getConnection()} 里等
 * {@code connectionTimeout}（10 秒）才失败 —— <b>这 10 秒里它一直占着一个 Web 线程</b>。
 * 并发一上来，Tomcat 的线程池会先于数据库被打满，表现成"整个平台都打不开"。
 *
 * <p>有了闸门之后，超出的请求在<b>碰到数据库之前</b>就被拒绝，
 * 用户拿到一句"当前繁忙，请稍后重试"，而不是一个 10 秒后超时的 500。
 *
 * <h2>⭐ 为什么是"立刻拒绝"而不是"排队等一会"</h2>
 *
 * <p>等待看起来更友好（请求最终会成功），但它把问题从"数据库连接不够"
 * 转移成了"Web 线程被占着"。而 Web 线程是<b>更大的池子、更贵的资源</b> ——
 * 用大资源去等小资源，是排队的经典反模式。
 *
 * <h2>⚠️ 它是 JVM 内的，多实例下每实例各有一份</h2>
 *
 * <p>这一点<b>和连接池是一致的</b>：{@code ConnectionManager} 的池子也是每实例一份。
 * 所以"每实例 N 个并发"和"每实例 N 个连接"是对齐的，不存在跨实例超发的问题。
 *
 * <h2>⚠️ 闸门对象的清理有个已知的、被接受的小瑕疵</h2>
 *
 * <p>{@code gates} 这两张表会随"用过的用户数 / 数据源数"增长，所以许可全部归还时会把条目摘掉。
 * 摘的时候有个很窄的竞态：一个线程刚归还完、判断"许可已满"，
 * 另一个线程恰好在这时拿到了<b>同一个</b> Semaphore 对象并 acquire，
 * 然后条目被摘掉 —— 后来的请求会新建一个，于是瞬时上限被轻微突破。
 *
 * <p><b>这个取舍是显式的</b>：为一个"软保护"引入引用计数或定期清理不划算，
 * 最多多放行几个请求，不会造成错误结果。
 * 而且 {@code query.guard.gates} 这个 Gauge 会把表的大小暴露出来 ——
 * 万一清理失效（表一直涨），看指标就能发现，而不是靠猜。
 */
@Slf4j
@Component
public class QueryConcurrencyGuard {

    /** 被拒绝的原因。用枚举而不是字符串：调用方要据此给<b>不同</b>的用户提示。 */
    public enum RejectReason {
        /** 通过了，可以执行。 */
        NONE,
        /** 这个人自己并发太多。 */
        PER_USER,
        /** 这个数据源当前太忙（别人也在用）。 */
        PER_DATA_SOURCE
    }

    private final int perDataSourceLimit;
    private final int perUserLimit;
    private final SqlPlatformMetrics metrics;

    /** 每个数据源一把闸门。key 是 dataSourceId —— 数量很少，且数据源被删时会连带清掉。 */
    private final ConcurrentHashMap<Long, Semaphore> dataSourceGates = new ConcurrentHashMap<>();

    /** 每个人一把闸门。key 是 userId —— 这张表才会随用户数增长，所以有清理逻辑。 */
    private final ConcurrentHashMap<Long, Semaphore> userGates = new ConcurrentHashMap<>();

    public QueryConcurrencyGuard(
            @Value("${platform.query-load.per-data-source-concurrency:" + ConnectionManager.MAX_POOL_SIZE + "}")
            int perDataSourceLimit,
            @Value("${platform.query-load.per-user-concurrency:3}") int perUserLimit,
            SqlPlatformMetrics metrics) {
        this.perDataSourceLimit = Math.max(1, perDataSourceLimit);
        this.perUserLimit = Math.max(1, perUserLimit);
        this.metrics = metrics;

        if (this.perDataSourceLimit > ConnectionManager.MAX_POOL_SIZE) {
            // 不抛异常：它只是个保护措施，配错了不该让应用起不来。
            // 但必须把后果说清楚 —— 否则运维会以为"我调大了并发能力"。
            log.warn("platform.query-load.per-data-source-concurrency({}) 大于连接池上限({})，"
                            + "多出来的并发仍会在 getConnection() 上等待连接，闸门形同虚设。",
                    this.perDataSourceLimit, ConnectionManager.MAX_POOL_SIZE);
        }

        // 把两张表的大小暴露成指标：清理逻辑万一失效，看指标就能发现
        metrics.registerConcurrencyGateGauges(dataSourceGates, userGates);
    }

    /**
     * 尝试进入执行。**非阻塞** —— 拿不到就立刻返回拒绝原因，不等待。
     *
     * <p>返回 {@link RejectReason#NONE} 时调用方<b>必须</b>在 {@code finally} 里调 {@link #release}，
     * 否则许可会永久泄漏、这个用户/数据源就再也进不来了。
     */
    public RejectReason tryAcquire(Long dataSourceId, Long userId) {
        Semaphore userGate = userGates.computeIfAbsent(userId, k -> new Semaphore(perUserLimit, true));
        if (!userGate.tryAcquire()) {
            metrics.recordQueryRejected("per-user");
            return RejectReason.PER_USER;
        }

        Semaphore dataSourceGate = dataSourceGates.computeIfAbsent(
                dataSourceId, k -> new Semaphore(perDataSourceLimit, true));
        if (!dataSourceGate.tryAcquire()) {
            // 数据源这一层没过，必须把上面那层已经拿到的许可还回去
            userGate.release();
            removeIfIdle(userGates, userId, userGate, perUserLimit);
            metrics.recordQueryRejected("per-data-source");
            return RejectReason.PER_DATA_SOURCE;
        }

        return RejectReason.NONE;
    }

    /** 释放许可。与 {@link #tryAcquire} 成对出现，必须在 {@code finally} 里调。 */
    public void release(Long dataSourceId, Long userId) {
        Semaphore dataSourceGate = dataSourceGates.get(dataSourceId);
        if (dataSourceGate != null) {
            dataSourceGate.release();
            removeIfIdle(dataSourceGates, dataSourceId, dataSourceGate, perDataSourceLimit);
        }

        Semaphore userGate = userGates.get(userId);
        if (userGate != null) {
            userGate.release();
            removeIfIdle(userGates, userId, userGate, perUserLimit);
        }
    }

    /**
     * 把拒绝原因翻译成<b>用户看得懂</b>的话。
     *
     * <p>两种原因必须分开说，因为<b>用户该做的事不一样</b>：
     * 前者是"你自己开太多了，等一下"，后者是"这个库现在被别人占着，稍后或换一个"。
     * 都回一句"系统繁忙"等于什么都没说。
     */
    public String rejectionMessage(RejectReason reason) {
        return switch (reason) {
            case PER_USER -> "你同时执行的查询太多了（上限 " + perUserLimit
                    + " 条），请等前面的执行完再试";
            case PER_DATA_SOURCE -> "该数据源当前繁忙（并发上限 " + perDataSourceLimit
                    + "），请稍后重试";
            case NONE -> "没有被拒绝";
        };
    }

    /**
     * 条目空闲（许可全部归还）时把它摘掉，避免表随用户数无限增长。
     *
     * <p>用 {@code remove(key, value)} 而不是 {@code remove(key)}：只有当表里还是<b>这一个</b>
     * 对象时才删。否则会把别人刚换上去的新闸门误删。见类注释里那条已知竞态。
     */
    private <K> void removeIfIdle(ConcurrentHashMap<K, Semaphore> gates, K key,
                                  Semaphore gate, int maxPermits) {
        if (gate.availablePermits() >= maxPermits) {
            gates.remove(key, gate);
        }
    }
}
