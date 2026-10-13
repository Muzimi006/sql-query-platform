package com.example.sqlquery.guard;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询的**并发**闸门。
 *
 * <p>它守的失败模式是"速率限制挡不住瞬时并发"：
 * 每个业务数据源的连接池只有 {@link ConnectionManager#MAX_POOL_SIZE} 个连接，
 * 而"每分钟 60 次"可以在一秒内全部打出去 —— 那时候它们一起去抢连接，
 * 第 6 个开始就要等满 {@code connectionTimeout}（10 秒），
 * 而这 10 秒它一直占着一个 Web 线程。
 *
 * <p>所以这个类要钉死三件事：
 * <ol>
 *   <li>超限**立刻**被拒（不是等、不是成功）；</li>
 *   <li>⭐ <b>数据源层被拒时，用户层的许可必须还回去</b> —— 这是个很容易漏的地方，
 *       漏了的表现是"用户被自己挡住"：几次数据源繁忙之后，这个人连空着的库也用不了了；</li>
 *   <li>许可全部归还后条目要被清理，否则表随用户数无限增长。</li>
 * </ol>
 */
class QueryConcurrencyGuardTest {

    private SimpleMeterRegistry registry;
    private SqlPlatformMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SqlPlatformMetrics(registry);
    }

    private QueryConcurrencyGuard guard(int perDataSource, int perUser) {
        return new QueryConcurrencyGuard(perDataSource, perUser, metrics);
    }

    // ------------------------------------------------------------------
    // 单用户并发上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个人只能同时跑 N 个查询，第 N+1 个立刻被拒")
    void perUserLimitShouldRejectImmediately() {
        QueryConcurrencyGuard guard = guard(5, 2);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));

        // ⭐ 关键：是**立刻**拒绝，不是排队等待
        assertEquals(QueryConcurrencyGuard.RejectReason.PER_USER, guard.tryAcquire(1L, 100L));
    }

    @Test
    @DisplayName("还回去之后又能进来了 —— 否则一次超限会把用户永久锁死")
    void releaseShouldRestorePermit() {
        QueryConcurrencyGuard guard = guard(5, 1);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        assertEquals(QueryConcurrencyGuard.RejectReason.PER_USER, guard.tryAcquire(1L, 100L));

        guard.release(1L, 100L);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
    }

    @Test
    @DisplayName("不同的人互不影响（各自的闸门是分开的）")
    void differentUsersShouldNotBlockEachOther() {
        QueryConcurrencyGuard guard = guard(5, 1);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 200L));
    }

    // ------------------------------------------------------------------
    // 每数据源并发上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个数据源：不同用户加起来也不能超过连接池上限")
    void perDataSourceLimitShouldCountAcrossUsers() {
        QueryConcurrencyGuard guard = guard(2, 10);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 200L));

        // 第三个人换了个用户，但数据源还是那一个 —— 必须被拒
        assertEquals(QueryConcurrencyGuard.RejectReason.PER_DATA_SOURCE, guard.tryAcquire(1L, 300L));
    }

    @Test
    @DisplayName("⭐ 数据源层被拒时，用户层的许可必须还回去 —— 否则用户会被自己挡住")
    void userPermitMustBeReturnedWhenDataSourceGateRejects() {
        // 用户上限放很宽（10），数据源上限只有 1 —— 让"数据源层被拒"必然发生
        QueryConcurrencyGuard guard = guard(1, 10);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));

        // 同一个人第二次进来：用户层过了（10 个许可），数据源层没过（只有 1 个）
        assertEquals(QueryConcurrencyGuard.RejectReason.PER_DATA_SOURCE, guard.tryAcquire(1L, 100L));
        // 再来几次，把"没还许可"这个泄漏放大
        for (int i = 0; i < 5; i++) {
            guard.tryAcquire(1L, 100L);
        }

        // 释放数据源上的那个持有者
        guard.release(1L, 100L);

        // ⭐ 现在这个用户应该能正常进来。如果上面那几次没还用户层的许可，
        //    用户层的 10 个许可会被慢慢吃光，这里就会变成 PER_USER（被自己挡住）。
        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L),
                "数据源被拒时没有归还用户层许可，用户被自己挡住了");
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("许可全部归还后条目要被摘掉，否则闸门表随用户数无限增长")
    void idleGatesShouldBeRemoved() {
        QueryConcurrencyGuard guard = guard(5, 5);

        guard.tryAcquire(1L, 100L);
        guard.release(1L, 100L);

        // 用 Gauge 读表大小 —— 顺便验证那个"清理失效能看得见"的指标真的接上了
        assertEquals(0.0, gaugeValue("data-source"));
        assertEquals(0.0, gaugeValue("user"));
    }

    private double gaugeValue(String scope) {
        return registry.get("query.guard.gates").tag("scope", scope).gauge().value();
    }

    @Test
    @DisplayName("还有人持有许可时不能清理（否则表里的限制会被绕过）")
    void gatesShouldNotBeRemovedWhileInUse() {
        QueryConcurrencyGuard guard = guard(5, 5);

        guard.tryAcquire(1L, 100L);          // 持有中
        assertEquals(1.0, gaugeValue("user"));

        guard.release(1L, 100L);
        assertEquals(0.0, gaugeValue("user"));
    }

    // ------------------------------------------------------------------
    // 指标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两种拒绝原因分开计数 —— 它们的处置完全不同")
    void rejectionsShouldBeTaggedByReason() {
        QueryConcurrencyGuard guard = guard(1, 1);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        guard.tryAcquire(1L, 100L);                          // per-user
        guard.tryAcquire(1L, 200L);                          // per-data-source

        assertEquals(1.0, registry.get("sql.query.rejected").tag("reason", "per-user").counter().count());
        assertEquals(1.0, registry.get("sql.query.rejected").tag("reason", "per-data-source").counter().count());
    }

    // ------------------------------------------------------------------
    // 真并发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 32 个线程同时抢：恰好只有上限数量的请求能通过")
    void concurrentAcquireShouldNeverExceedTheLimit() throws Exception {
        int limit = 4;
        int threads = 32;
        QueryConcurrencyGuard guard = guard(limit, limit);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();          // 卡在同一时刻放出去
                        if (guard.tryAcquire(1L, 100L) == QueryConcurrencyGuard.RejectReason.NONE) {
                            acquired.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发测试超时");

            // 拿到的**恰好**是上限：多了说明闸门漏，少了说明 Semaphore 用错了
            assertEquals(limit, acquired.get(),
                    "32 个线程同时抢 " + limit + " 个许可，应该恰好 " + limit + " 个通过");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("释放之后再并发抢，仍然能拿满额度")
    void limitShouldRecoverAfterRelease() throws Exception {
        int limit = 2;
        QueryConcurrencyGuard guard = guard(limit, limit);

        guard.tryAcquire(1L, 100L);
        guard.tryAcquire(1L, 100L);
        assertNotEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));

        guard.release(1L, 100L);
        guard.release(1L, 100L);

        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
        assertEquals(QueryConcurrencyGuard.RejectReason.NONE, guard.tryAcquire(1L, 100L));
    }
}
