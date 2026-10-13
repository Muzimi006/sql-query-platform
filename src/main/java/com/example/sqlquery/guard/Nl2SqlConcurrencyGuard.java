package com.example.sqlquery.guard;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * NL2SQL 的<b>并发</b>上限，JVM 内兜底。
 *
 * <h2>它补的是什么洞</h2>
 *
 * <p>NL2SQL 入口原先只有一道"频率"限流（默认 5 次 / 60 秒）。
 * 频率限制回答的是"你一分钟点了多少次"，<b>不回答"现在有多少个请求同时在跑"</b> ——
 * 两者在慢操作上完全不是一回事：一次生成要等一次外部模型调用（超时按秒甚至几十秒计），
 * 用户点满 5 次之后每一次都还挂在模型调用上，第 6 次才被拦。
 * <b>频率上限 × 单次耗时 = 实际的并发占用</b>，这个乘积原先没有任何上界。
 *
 * <p>被占用的是什么：Tomcat 线程（等 HTTP 响应）、外部模型配额、
 * 以及生成后落工单那一步用的数据库连接。
 * 这三样都是全平台共享的，所以一个人反复点就能影响到所有人。
 *
 * <h2>为什么是"等一会儿"而不是"立刻拒绝"</h2>
 *
 * <p>这是它与 {@link QueryConcurrencyGuard} 的一个刻意差别：那个是非阻塞的
 * （查询执行是用户在页面上等着看结果的短操作，排队等待只会让所有人都变慢）；
 * 而 NL2SQL 本身就是秒级操作，<b>等一下再进去比直接被拒绝体验好得多</b>，
 * 也不会让用户以为功能坏了。所以这里给一个很短的等待窗口，超时才拒绝。
 *
 * <h2>⚠️ 它守不住什么（必须说清）</h2>
 *
 * <p>这是<b>单进程</b>的闸门。多实例部署时每个实例各有一份许可，
 * 全局并发上限是"实例数 × 这个值"。要真正全局守住得放在网关或 Redis 上，
 * 但那会引入一次额外的网络往返 —— 对一个"已经要调外部模型"的操作来说，
 * 这一层的价值是<b>防止单实例被一个人打满</b>，不是精确配额。
 */
@Slf4j
@Component
public class Nl2SqlConcurrencyGuard {

    private final Semaphore gate;

    /** 同时在跑的生成任务上限。 */
    private final int maxConcurrent;

    /** 拿不到许可时最多等多久（毫秒）。 */
    private final long acquireTimeoutMs;

    private final SqlPlatformMetrics metrics;

    public Nl2SqlConcurrencyGuard(
            @Value("${platform.llm.max-concurrent:4}") int maxConcurrent,
            @Value("${platform.llm.acquire-timeout-ms:2000}") long acquireTimeoutMs,
            SqlPlatformMetrics metrics) {
        // 至少 1：配成 0 或负数会让整个功能永久不可用，而这种配置错误
        // 不应该表现为"点了没反应"
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
        this.metrics = metrics;
        // 公平锁：先到的先拿到许可。否则高峰期后到的请求可能一直插队，
        // 表现为"有的人等 2 秒能进去，有的人每次都超时"
        this.gate = new Semaphore(this.maxConcurrent, true);

        if (maxConcurrent <= 0) {
            log.warn("platform.llm.max-concurrent 配成了 {}，已按 1 处理。", maxConcurrent);
        }
    }

    /**
     * 尝试占用一个并发额度。拿到返回 {@code true}，此时调用方<b>必须</b>在
     * {@code finally} 里调 {@link #release()}，否则许可泄漏、额度会被永久吃掉。
     */
    public boolean tryAcquire() {
        try {
            if (gate.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            // 中断要还原标志位：吞掉中断会让上层（比如优雅停机的线程池）
            // 以为没人要求它停下
            Thread.currentThread().interrupt();
        }

        // 只在"真的没挤进去"时计数。中断走的也是这条路 —— 对用户来说结果一样是没进去
        metrics.recordNl2sqlRejected();
        return false;
    }

    /** 归还额度。与 {@link #tryAcquire} 成对出现，必须在 {@code finally} 里调。 */
    public void release() {
        gate.release();
    }

    /** 当前正在跑的生成任务数，仅用于日志与测试断言。 */
    public int inFlight() {
        return maxConcurrent - gate.availablePermits();
    }
}
