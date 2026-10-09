package com.example.sqlquery.service.impl;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.cache.DbSourceCacheInvalidator;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缓存击穿的回归测试 —— <b>它证明的是"根除"，不是"降低概率"</b>。
 *
 * <p>击穿的场景：某个热 key 过期的那一瞬间，大量并发请求同时发现缓存没了，
 * 一起涌向数据库。原来的实现是"抢不到 Redis 锁就 sleep(50) 再读一次缓存，
 * 还没命中就直接查库"，等待者最终仍然会查库，只是把洪峰摊平了一点。
 *
 * <p>本用例把 32 个线程用 {@link CountDownLatch} 卡在同一时刻放出去，
 * 全部请求同一个 key，然后断言<b>数据库只被访问了一次</b>。
 * 用假的 Redis（真 ConcurrentHashMap 支撑）而不是 mock 掉整个缓存，
 * 是为了让"第一个线程写进缓存、其余线程能读到"这条链路真的跑起来 ——
 * 如果缓存永远读不到东西，这个测试会以 32 次回源失败，而那是测试自己造出来的假象。
 *
 * <p>另外两个断言守的是"空值缓存"：查不到的 id 也要缓存起来，
 * 否则每次请求一个不存在的 id 都会穿透到数据库 —— 这是另一种形式的击穿。
 */
class DbSourceCacheStampedeTest {

    private static final Long USER_ID = 1L;
    private static final Long SOURCE_ID = 100L;

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private DbSourceServiceImpl service;

    /** 假 Redis：用真的 ConcurrentHashMap 支撑读写，语义与真实缓存一致。 */
    private final ConcurrentHashMap<String, String> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);

        Mockito.when(valueOps.get(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        Mockito.doAnswer(inv -> {
            redisStore.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valueOps).set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Duration.class));
        // 缓存 miss 之后由调用线程去抢分布式锁，这里直接判它抢到
        Mockito.when(valueOps.setIfAbsent(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Duration.class))).thenReturn(true);

        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // delete / execute 不显式打桩：默认返回 null 对被测逻辑没有影响
        // （删缓存与释放锁的返回值都被忽略，失败也只记日志）

        objectMapper = new ObjectMapper();
        // 本用例只覆盖"读缓存 / 回源"，失效器不会被调用，给个 mock 即可
        service = Mockito.spy(new DbSourceServiceImpl(
                null, redisTemplate, objectMapper, Mockito.mock(DbSourceCacheInvalidator.class),
                new SqlPlatformMetrics(new SimpleMeterRegistry())));
    }

    private DbSource existingSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(SOURCE_ID);
        dbSource.setUserId(USER_ID);
        dbSource.setName("压测数据源");
        dbSource.setStatus(1);
        return dbSource;
    }

    /** 把 n 个线程卡在同一时刻放出去，全部执行同一个动作。 */
    private void runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    action.run();
                } catch (Exception ignored) {
                    // 单个线程的异常不影响"回源次数"这个观测目标
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("32 个并发请求同一个 key：数据库必须只被回源 1 次")
    void concurrentRequestsShouldHitDatabaseOnlyOnce() throws Exception {
        Mockito.doReturn(existingSource()).when(service).getById(SOURCE_ID);

        AtomicInteger returned = new AtomicInteger();
        runConcurrently(32, () -> {
            DbSource got = service.getByIdAndUserId(USER_ID, SOURCE_ID);
            if (got != null && SOURCE_ID.equals(got.getId())) {
                returned.incrementAndGet();
            }
        });

        // 核心断言：回源次数必须是 1。
        // 老实现（sleep 50ms 后仍回源）在这里会得到接近 32 的数字。
        Mockito.verify(service, Mockito.times(1)).getById(SOURCE_ID);
        assertEquals(32, returned.get(), "所有 32 个线程都应该拿到数据源（不能因为限流式等待而失败）");
    }

    @Test
    @DisplayName("第二次请求应该直接命中缓存，不再回源")
    void secondRequestShouldHitCache() {
        Mockito.doReturn(existingSource()).when(service).getById(SOURCE_ID);

        service.getByIdAndUserId(USER_ID, SOURCE_ID);
        service.getByIdAndUserId(USER_ID, SOURCE_ID);
        service.getByIdAndUserId(USER_ID, SOURCE_ID);

        Mockito.verify(service, Mockito.times(1)).getById(SOURCE_ID);
    }

    @Test
    @DisplayName("不存在的 id 也要缓存：连续请求不应该每次都穿透到数据库")
    void missingIdShouldBeNegativeCached() {
        Mockito.doReturn(null).when(service).getById(SOURCE_ID);

        for (int i = 0; i < 5; i++) {
            try {
                service.getByIdAndUserId(USER_ID, SOURCE_ID);
            } catch (BusinessException expected) {
                // 预期：数据源不存在
            }
        }

        // 空值缓存的意义就在这里：5 次请求只查 1 次库
        Mockito.verify(service, Mockito.times(1)).getById(SOURCE_ID);
    }

    @Test
    @DisplayName("访问别人的数据源同样按不存在处理（不能因为缓存而绕过归属校验）")
    void otherUsersSourceShouldBeTreatedAsMissing() {
        DbSource otherUsers = existingSource();
        otherUsers.setUserId(999L);
        Mockito.doReturn(otherUsers).when(service).getById(SOURCE_ID);

        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            try {
                service.getByIdAndUserId(USER_ID, SOURCE_ID);
            } catch (BusinessException e) {
                messages.add(e.getMessage());
            }
        }

        assertEquals(3, messages.size(), "三次都应该被拒绝");
        assertEquals(1, messages.stream().distinct().count(), "拒绝原因必须一致");
        Mockito.verify(service, Mockito.times(1)).getById(SOURCE_ID);
    }
}
