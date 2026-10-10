package com.example.sqlquery.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标定义本身的行为。
 *
 * <p>用 {@link SimpleMeterRegistry} 而不是起 Spring 上下文：
 * 这个类要验的是"指标名、标签、数值对不对"，跟 Spring 装配没关系，
 * 而装配错误（比如 {@code MeterRegistry} 没注入）会在应用启动时立刻暴露，
 * 用集成测试更合适。
 *
 * <p>这里守的是两个容易出问题的点：
 * <ol>
 *   <li><b>标签必须分开计数</b>：成功和失败的耗时混在一个时间线里，
 *       会把真实的慢查询掩盖掉；</li>
 *   <li><b>连接池指标必须能注销</b>：数据源可以被反复增删，
 *       不注销就是随操作次数增长的指标基数泄漏。</li>
 * </ol>
 */
class SqlPlatformMetricsTest {

    private SimpleMeterRegistry registry;
    private SqlPlatformMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SqlPlatformMetrics(registry);
    }

    @Test
    @DisplayName("成功与失败的耗时分属两条时间线，不会互相掩盖")
    void successAndFailureDurationShouldBeTaggedSeparately() {
        metrics.recordQuerySuccess(10);
        metrics.recordQuerySuccess(20);
        metrics.recordQueryFailure(3);

        Timer success = registry.find(SqlPlatformMetrics.QUERY_DURATION).tag("outcome", "success").timer();
        Timer failed = registry.find(SqlPlatformMetrics.QUERY_DURATION).tag("outcome", "failed").timer();

        assertNotNull(success, "成功耗时的计时器没注册");
        assertNotNull(failed, "失败耗时的计时器没注册");
        assertEquals(2, success.count());
        assertEquals(1, failed.count());
        // 合并成一条的话，P50 会被那些"很快就失败"的请求拉到很低
        assertTrue(success.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
    }

    @Test
    @DisplayName("截断与限流各自独立计数")
    void truncationAndRateLimitShouldBeCountedSeparately() {
        metrics.recordQueryTruncated();
        metrics.recordQueryTruncated();
        metrics.recordRateLimited("query");

        Counter truncated = registry.find(SqlPlatformMetrics.QUERY_TRUNCATED).counter();
        Counter limited = registry.find(SqlPlatformMetrics.QUERY_RATE_LIMITED)
                .tag("scope", "query").counter();

        assertEquals(2.0, truncated.count());
        assertEquals(1.0, limited.count());
    }

    @Test
    @DisplayName("七道限流共用同一个计数器，必须靠 scope 标签区分")
    void rateLimitCounterShouldCarryScopeTag() {
        metrics.recordRateLimited("login-ip");
        metrics.recordRateLimited("login-account");
        metrics.recordRateLimited("query");

        // "哪一道限流在拦人"是看板要回答的第一个问题：
        // 不带标签的话，登录被撞库刷爆和某个用户刷查询导出的是同一个数字，
        // 看到它在涨也不知道该动哪个阈值。
        assertEquals(1.0, registry.find(SqlPlatformMetrics.QUERY_RATE_LIMITED)
                .tag("scope", "login-ip").counter().count());
        assertEquals(1.0, registry.find(SqlPlatformMetrics.QUERY_RATE_LIMITED)
                .tag("scope", "login-account").counter().count());
        assertEquals(1.0, registry.find(SqlPlatformMetrics.QUERY_RATE_LIMITED)
                .tag("scope", "query").counter().count());
        assertNull(registry.find(SqlPlatformMetrics.QUERY_RATE_LIMITED)
                        .tag("scope", "nl2sql").counter(),
                "没有触发过的 scope 不该凭空出现一条时间线");
    }

    @Test
    @DisplayName("缓存命中与未命中分开计数，相除就是命中率")
    void cacheHitAndMissShouldBeCountedSeparately() {
        metrics.recordCacheHit();
        metrics.recordCacheHit();
        metrics.recordCacheHit();
        metrics.recordCacheMiss();

        assertEquals(3.0, registry.find(SqlPlatformMetrics.CACHE_HIT).counter().count());
        assertEquals(1.0, registry.find(SqlPlatformMetrics.CACHE_MISS).counter().count());
    }

    @Test
    @DisplayName("连接池指标：读数从 MXBean 透传，且按数据源打标签")
    void poolGaugesShouldReflectPoolState() {
        HikariDataSource pool = newPool(3, 2, 5, 1);

        metrics.registerPoolGauges(7L, pool);

        assertEquals(3.0, gaugeValue("datasource.pool.connections.active", "7"));
        assertEquals(2.0, gaugeValue("datasource.pool.connections.idle", "7"));
        assertEquals(5.0, gaugeValue("datasource.pool.connections.total", "7"));
        // waiting > 0 是"池子不够用"最直接的信号，必须真的读出来
        assertEquals(1.0, gaugeValue("datasource.pool.connections.waiting", "7"));
    }

    @Test
    @DisplayName("注销之后指标要从注册表里消失，否则反复增删数据源会撑大指标基数")
    void poolGaugesShouldBeRemovable() {
        metrics.registerPoolGauges(7L, newPool(1, 1, 1, 0));
        assertNotNull(registry.find("datasource.pool.connections.active")
                .tag("dataSourceId", "7").gauge());

        metrics.removePoolGauges(7L);

        assertNull(registry.find("datasource.pool.connections.active")
                        .tag("dataSourceId", "7").gauge(),
                "注销之后指标应该从注册表里消失");
    }

    @Test
    @DisplayName("重新注册同一个数据源：不应留下两条同名同标签的时序")
    void reRegisteringShouldReplacePreviousGauges() {
        metrics.registerPoolGauges(8L, newPool(1, 0, 1, 0));
        metrics.registerPoolGauges(8L, newPool(4, 0, 4, 0));

        assertEquals(1, registry.find("datasource.pool.connections.active")
                .tag("dataSourceId", "8").gauges().size(),
                "同名同标签的指标只能有一条，否则 Prometheus 会报重复时序");
        // 读到的应该是新池子的值，不是被替换掉的那个
        assertEquals(4.0, gaugeValue("datasource.pool.connections.active", "8"));
    }

    @Test
    @DisplayName("Redis 降级按原因分标签计数 —— 这是'Redis 挂了'唯一可靠的观测点")
    void redisDegradationShouldBeCountedByReason() {
        var cause = new RuntimeException("Unable to connect to Redis");

        metrics.recordRedisDegradation("cache-read", "cacheKey=datasource:1", cause);
        metrics.recordRedisDegradation("cache-read", "cacheKey=datasource:2", cause);
        metrics.recordRedisDegradation("blacklist-read", "token 检查", cause);

        assertEquals(2.0, registry.get("redis.degradation").tag("reason", "cache-read").counter().count());
        assertEquals(1.0, registry.get("redis.degradation").tag("reason", "blacklist-read").counter().count());
    }

    @Test
    @DisplayName("⭐ 计数不受日志限流影响：重复降级只打一条日志，但计数必须每次都加")
    void countingMustNotBeSuppressedByLogThrottling() {
        var cause = new RuntimeException("Unable to connect to Redis");

        for (int i = 0; i < 500; i++) {
            metrics.recordRedisDegradation("blacklist-read", "第 " + i + " 次", cause);
        }

        // 日志被限流成 1 条，但计数是 500 —— 这正是"降级日志降为 WARN + 限流"之后
        // 仍然能告警的前提：日志负责"长什么样"，计数器负责"有多少"。
        assertEquals(500.0, registry.get("redis.degradation").tag("reason", "blacklist-read").counter().count());
    }

    @Test
    @DisplayName("降级记录不能因为 cause 为 null 而抛异常")
    void nullCauseShouldBeTolerated() {
        metrics.recordRedisDegradation("cache-write", "cacheKey=datasource:9", null);

        assertEquals(1.0, registry.get("redis.degradation").tag("reason", "cache-write").counter().count());
    }

    @Test
    @DisplayName("连接池尚未启动（MXBean 为 null）时读数为 0，不能抛异常")
    void gaugeShouldSurviveNullPoolBean() {
        HikariDataSource notStarted = org.mockito.Mockito.mock(HikariDataSource.class);
        org.mockito.Mockito.when(notStarted.getHikariPoolMXBean()).thenReturn(null);

        metrics.registerPoolGauges(9L, notStarted);

        // Gauge 在抓取时抛异常会让整个 /actuator/prometheus 返回 500，
        // 一条指标的问题会连带把其它指标一起弄没
        assertEquals(0.0, gaugeValue("datasource.pool.connections.active", "9"));
    }

    private double gaugeValue(String name, String dataSourceId) {
        Gauge gauge = registry.find(name).tag("dataSourceId", dataSourceId).gauge();
        assertNotNull(gauge, "指标没注册上：" + name);
        return gauge.value();
    }

    /**
     * 造一个假的连接池：只实现"读水位"这一条路径。
     *
     * <p>不用真池子是因为那需要 classpath 上有对应 JDBC 驱动，
     * 而本用例要验的是"读数有没有从 MXBean 透传到 Gauge"，
     * 与真实连接没有任何关系。
     */
    @Test
    @DisplayName("⭐ 投递失败按链路分标签 —— 两条链路的处置不同，指标更不能混")
    void publishFailureShouldBeTaggedByLink() {
        // 为什么值得单独钉一条：这两个计数器是「Broker 出问题了」唯一可靠的观测点。
        // 两条链路对失败的处置完全相反（工单吞掉等补偿重投 / 导出立刻标失败），
        // 混成一个 counter 的话，看板上只能看到「投递失败 3 次」，
        // 而不知道该去查工单的 APPROVED 积压还是导出的失败任务。
        metrics.recordPublishFailure(SqlPlatformMetrics.LINK_REVIEW);
        metrics.recordPublishFailure(SqlPlatformMetrics.LINK_REVIEW);
        metrics.recordPublishFailure(SqlPlatformMetrics.LINK_EXPORT);

        assertEquals(2.0, registry.get(SqlPlatformMetrics.PUBLISH_FAILED)
                .tag("link", SqlPlatformMetrics.LINK_REVIEW).counter().count());
        assertEquals(1.0, registry.get(SqlPlatformMetrics.PUBLISH_FAILED)
                .tag("link", SqlPlatformMetrics.LINK_EXPORT).counter().count());
    }

    @Test
    @DisplayName("投递失败计数器天然可告警：初始必须为 0，且不会被别的路径误增")
    void publishFailureShouldStartAtZero() {
        // 「持续大于 0 就是 Broker 出问题了」——这句告警规则要成立，
        // 前提是**正常运行时它必须稳定在 0**。
        // 这里顺带钉住它不会被别的路径误增：跑一遍其它指标方法，它必须纹丝不动。
        metrics.recordQuerySuccess(10);
        metrics.recordQueryFailure(10);
        metrics.recordQueryTruncated();
        metrics.recordRateLimited("query");
        metrics.recordQueryRejected("per-user");
        metrics.recordCircuitRejected();
        metrics.recordCircuitOpened();
        metrics.recordCacheHit();
        metrics.recordCacheMiss();
        metrics.recordConsumerRetry("export.queue");

        assertNull(registry.find(SqlPlatformMetrics.PUBLISH_FAILED).counter(),
                "没有任何投递失败时，这个计数器不该存在（更不该大于 0）");
    }
    private HikariDataSource newPool(int active, int idle, int total, int waiting) {
        HikariPoolMXBean bean = org.mockito.Mockito.mock(HikariPoolMXBean.class);
        org.mockito.Mockito.when(bean.getActiveConnections()).thenReturn(active);
        org.mockito.Mockito.when(bean.getIdleConnections()).thenReturn(idle);
        org.mockito.Mockito.when(bean.getTotalConnections()).thenReturn(total);
        org.mockito.Mockito.when(bean.getThreadsAwaitingConnection()).thenReturn(waiting);

        HikariDataSource pool = org.mockito.Mockito.mock(HikariDataSource.class);
        org.mockito.Mockito.when(pool.getHikariPoolMXBean()).thenReturn(bean);
        return pool;
    }
}
