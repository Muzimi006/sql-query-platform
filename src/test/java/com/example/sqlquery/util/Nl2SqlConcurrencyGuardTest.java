package com.example.sqlquery.util;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NL2SQL 的并发兜底闸门。
 *
 * <p>它守的是一条很容易被忽略的等式：
 * <b>频率上限 × 单次耗时 = 实际并发占用</b>。
 * 入口有"5 次 / 60 秒"的频率限制，但一次生成最长占 30 秒 Web 线程，
 * 于是用户在 60 秒里点 5 次，就有 5 个线程一起挂在模型调用上 ——
 * 频率限制对此完全无感。
 */
class Nl2SqlConcurrencyGuardTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);

    private Nl2SqlConcurrencyGuard guard(int maxConcurrent, long timeoutMs) {
        return new Nl2SqlConcurrencyGuard(maxConcurrent, timeoutMs, metrics);
    }

    private double rejected() {
        var counter = registry.find(SqlPlatformMetrics.NL2SQL_REJECTED).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("额度用完后必须拒绝，而不是排队等到天荒地老")
    void shouldRejectWhenSaturated() {
        Nl2SqlConcurrencyGuard g = guard(2, 50);

        assertTrue(g.tryAcquire());
        assertTrue(g.tryAcquire());
        assertEquals(2, g.inFlight());

        long start = System.currentTimeMillis();
        assertFalse(g.tryAcquire(), "额度已经用完了，第三次必须被拒绝");
        long waited = System.currentTimeMillis() - start;

        // 它确实**等了一会儿**（而不是立刻拒绝）—— 这是刻意的：
        // NL2SQL 本身是秒级操作，等一下再进去比直接被拒体验好。
        assertTrue(waited >= 40, "应该先等一个很短的窗口再拒绝，实际只等了 " + waited + "ms");
        assertEquals(1.0, rejected(), "拒绝必须计数：它是\"该扩容了\"的唯一信号");
    }

    @Test
    @DisplayName("释放之后额度必须真的还回来（否则每失败一次就永久少一个额度）")
    void releaseShouldReturnPermit() {
        Nl2SqlConcurrencyGuard g = guard(1, 0);

        assertTrue(g.tryAcquire());
        assertFalse(g.tryAcquire());

        g.release();

        assertEquals(0, g.inFlight());
        assertTrue(g.tryAcquire(), "归还额度之后必须能再进来");
        // 只有上面那一次"额度用完"被拒绝，归还之后不该再增加
        assertEquals(1.0, rejected(), "这一次没有被拒绝，计数不该动");
    }

    @Test
    @DisplayName("并发中的请求在别人释放后会拿到额度（等的是许可，不是固定睡一觉）")
    void waitingThreadShouldBeWokenByRelease() throws Exception {
        Nl2SqlConcurrencyGuard g = guard(1, 2000);
        assertTrue(g.tryAcquire());

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean acquired = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            acquired.set(g.tryAcquire());
            done.countDown();
        });
        waiter.start();

        Thread.sleep(100);
        assertFalse(acquired.get(), "额度还没还，它不该已经进去");
        g.release();

        assertTrue(done.await(2, TimeUnit.SECONDS), "释放后等待的线程没有被唤醒");
        assertTrue(acquired.get());
        assertEquals(0.0, rejected());
    }

    @Test
    @DisplayName("配成 0 或负数按 1 处理：配置错误不该让功能变成\"点了没反应\"")
    void nonPositiveLimitShouldFallBackToOne() {
        Nl2SqlConcurrencyGuard g = guard(0, 0);

        assertTrue(g.tryAcquire(), "至少要有 1 个额度，否则功能永久不可用");
        assertFalse(g.tryAcquire());
    }

    @Test
    @DisplayName("中断要还原标志位，不能被当成\"拿到了额度\"")
    void interruptShouldNotBeSwallowed() throws Exception {
        Nl2SqlConcurrencyGuard g = guard(1, 5000);
        assertTrue(g.tryAcquire());

        AtomicBoolean acquired = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> acquired.set(g.tryAcquire()));
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(2000);

        assertFalse(acquired.get(), "被中断时不能算拿到了额度");
        assertEquals(1.0, rejected(), "没进去就算一次拒绝");
    }
}
