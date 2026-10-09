package com.example.sqlquery.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * 平台自己的指标体系。
 *
 * <h2>为什么全部集中在这一个类里</h2>
 *
 * <p>指标名和标签是<b>对外契约</b>：一旦有告警规则或看板按名字取值，
 * 改名字就等于把告警悄悄关掉 —— 而且不会有任何编译错误。
 * 散落在各个 Service 里 {@code registry.counter("xxx")} 时，
 * 同一个语义很容易在两个地方被写成两个名字（{@code sql.query.failed} /
 * {@code query.failure.count}），看板上就永远是两条断掉的线。
 *
 * <p>集中之后还有两个好处：命名可以一眼看全、不会撞车；
 * 以及可以用一个 {@code SimpleMeterRegistry} 在单元测试里直接断言，
 * 不需要起 Spring 上下文。
 *
 * <h2>覆盖的是 README「已知限制」里点名的四项</h2>
 *
 * <table border="1">
 *   <caption>限制 → 指标</caption>
 *   <tr><th>原来"不可观测"的东西</th><th>对应的指标</th></tr>
 *   <tr><td>慢查询</td><td>{@code sql.query.duration}（带 P50/P95/P99）</td></tr>
 *   <tr><td>连接池水位</td><td>{@code datasource.pool.connections.*}（每个业务数据源一组）</td></tr>
 *   <tr><td>缓存命中率</td><td>{@code datasource.cache.hit} / {@code .miss}</td></tr>
 *   <tr><td>限流触发次数</td><td>{@code sql.query.rate_limited}</td></tr>
 * </table>
 */
@Slf4j
@Component
public class SqlPlatformMetrics {

    /** 用户 SQL 的执行耗时，按结果分标签。 */
    public static final String QUERY_DURATION = "sql.query.duration";

    /** 结果被截断的次数（超过单次返回上限）。 */
    public static final String QUERY_TRUNCATED = "sql.query.truncated";

    /** 被执行次数上限拦下的次数。 */
    public static final String QUERY_RATE_LIMITED = "sql.query.rate_limited";

    /** 数据源缓存命中 / 未命中，两者相除就是命中率。 */
    public static final String CACHE_HIT = "datasource.cache.hit";
    public static final String CACHE_MISS = "datasource.cache.miss";

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_DATA_SOURCE = "dataSourceId";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    private final Counter queryTruncated;
    private final Counter queryRateLimited;
    private final Counter cacheHit;
    private final Counter cacheMiss;

    /**
     * 每个业务数据源连接池注册的 Gauge。
     *
     * <p>保存引用是<b>为了能注销</b>。Micrometer 的注册表不会自己清理：
     * 数据源删掉之后，那几条带 {@code dataSourceId=7} 标签的时序会永远留在
     * 注册表里，Prometheus 那边也会一直存在一条不再更新的时间线。
     * 数据源可以被反复增删，不注销就是一个随操作次数增长的内存与指标基数泄漏。
     */
    private final Map<Long, List<Gauge>> poolGauges = new ConcurrentHashMap<>();

    public SqlPlatformMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.queryTruncated = Counter.builder(QUERY_TRUNCATED)
                .description("结果集超过单次返回上限而被截断的次数")
                .register(registry);

        this.queryRateLimited = Counter.builder(QUERY_RATE_LIMITED)
                .description("被单用户执行频率上限拦下的次数")
                .register(registry);

        this.cacheHit = Counter.builder(CACHE_HIT)
                .description("数据源元数据缓存命中次数")
                .register(registry);

        this.cacheMiss = Counter.builder(CACHE_MISS)
                .description("数据源元数据缓存未命中（需要回源数据库）的次数")
                .register(registry);
    }

    /**
     * 记录一次 SQL 执行。
     *
     * <p>成功与失败<b>分开打标签</b>而不是只记一个总耗时：
     * 失败通常很快就返回（语法错误、权限拦截），把它们混在一起算 P95，
     * 会把真实的慢查询掩盖掉 —— 这是"有指标但看不出问题"的典型原因。
     */
    public void recordQuery(String outcome, long costMs) {
        Timer.builder(QUERY_DURATION)
                .description("用户 SQL 的执行耗时")
                .tag(TAG_OUTCOME, outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(Math.max(costMs, 0)));
    }

    public void recordQuerySuccess(long costMs) {
        recordQuery(OUTCOME_SUCCESS, costMs);
    }

    public void recordQueryFailure(long costMs) {
        recordQuery(OUTCOME_FAILED, costMs);
    }

    public void recordQueryTruncated() {
        queryTruncated.increment();
    }

    public void recordRateLimited() {
        queryRateLimited.increment();
    }

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    /**
     * 为一个业务数据源连接池注册水位指标。
     *
     * <p>四个值放在一起看才说明问题：
     * <ul>
     *   <li>{@code active} 长期接近 {@code max}（当前是 5）→ 池子太小，请求在排队；</li>
     *   <li>{@code waiting} 大于 0 → <b>已经</b>有线程在等连接了，这是最直接的信号；</li>
     *   <li>{@code idle} 长期等于 {@code max} → 池子开得太大，白白占着数据库连接；</li>
     *   <li>{@code total} 是当前实际持有数，用于看池子有没有按预期回收。</li>
     * </ul>
     */
    public void registerPoolGauges(Long dataSourceId, HikariDataSource pool) {
        // ⚠️ 必须先注销、再注册，顺序不能反。
        // Micrometer 的注册表是按「名字 + 标签」索引的，新旧指标的 Id 完全相同 ——
        // 写成"先注册新的、再 remove 掉旧的 Gauge 对象"，实际删掉的是<b>刚注册的那一条</b>
        // （remove 按 Id 匹配，不区分对象）。结果是重新注册之后指标反而没了。
        // 这个坑是单元测试 reRegisteringShouldReplacePreviousGauges 抓出来的。
        removePoolGauges(dataSourceId);

        String id = String.valueOf(dataSourceId);

        List<Gauge> gauges = new ArrayList<>();
        gauges.add(poolGauge("datasource.pool.connections.active", "正在被使用的连接数",
                id, pool, p -> poolBean(p).getActiveConnections()));
        gauges.add(poolGauge("datasource.pool.connections.idle", "空闲连接数",
                id, pool, p -> poolBean(p).getIdleConnections()));
        gauges.add(poolGauge("datasource.pool.connections.total", "当前持有的连接总数",
                id, pool, p -> poolBean(p).getTotalConnections()));
        gauges.add(poolGauge("datasource.pool.connections.waiting", "正在等待连接的线程数",
                id, pool, p -> poolBean(p).getThreadsAwaitingConnection()));

        poolGauges.put(dataSourceId, gauges);
    }

    /** 注销某个数据源的连接池指标。数据源被删除或连接池被驱逐时调用。 */
    public void removePoolGauges(Long dataSourceId) {
        List<Gauge> gauges = poolGauges.remove(dataSourceId);
        if (gauges != null) {
            gauges.forEach(registry::remove);
        }
    }

    private Gauge poolGauge(String name, String description, String dataSourceId,
                            HikariDataSource pool, ToDoubleFunction<HikariDataSource> value) {
        return Gauge.builder(name, pool, value)
                .description(description)
                .tag(TAG_DATA_SOURCE, dataSourceId)
                // 强引用：Gauge 默认弱引用目标对象，池子被回收后读数会变成 0，
                // 那种"看起来连接数掉到 0"的假象比没有指标更容易误导人
                .strongReference(true)
                .register(registry);
    }

    /** 连接池还没启动时拿不到 MXBean，这里统一按 0 处理，避免 Gauge 抛异常。 */
    private HikariPoolMXBean poolBean(HikariDataSource pool) {
        HikariPoolMXBean bean = pool.getHikariPoolMXBean();
        return bean == null ? EMPTY_POOL_BEAN : bean;
    }

    /** 池子尚未启动时的占位实现：所有读数都是 0。 */
    private static final HikariPoolMXBean EMPTY_POOL_BEAN = new HikariPoolMXBean() {
        @Override public int getIdleConnections() { return 0; }
        @Override public int getActiveConnections() { return 0; }
        @Override public int getTotalConnections() { return 0; }
        @Override public int getThreadsAwaitingConnection() { return 0; }
        @Override public void softEvictConnections() { }
        @Override public void suspendPool() { }
        @Override public void resumePool() { }
    };
}
