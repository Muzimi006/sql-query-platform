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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * 被<b>并发</b>闸门拦下的次数，按原因分标签。
     *
     * <p>⚠️ 它和 {@link #QUERY_RATE_LIMITED} 是<b>两件事</b>，不要混：
     * 那个是"你刷得太快"（单位时间次数），这个是"你（或这个数据源）同时在跑的太多了"。
     * 两者的处置也不同 —— 前者让用户等一会，后者说明<b>连接池已经到顶、该扩容或限流了</b>。
     */
    public static final String QUERY_REJECTED = "sql.query.rejected";

    /** 并发闸门表的当前大小（数据源 / 用户两张）。用来看清理逻辑有没有失效。 */
    public static final String GUARD_GATES = "query.guard.gates";

    /** 被熔断器直接拒绝的次数（没去尝试连接）。 */
    public static final String CIRCUIT_REJECTED = "datasource.circuit.rejected";

    /** 熔断器打开的次数（失败达到阈值、进入熔断）。 */
    public static final String CIRCUIT_OPENED = "datasource.circuit.opened";

    /*
     * ⚠️ 这两个计数器**刻意不带 dataSourceId 标签**。
     *
     * 连接池那几个 Gauge 是带 dataSourceId 的，因为它们可以在数据源被删除时
     * 显式注销（见 removePoolGauges）。而 **Counter 没法这样注销** ——
     * 带上去之后，每删一个数据源就在 Prometheus 里留下一条永远不再更新、
     * 也永远不会消失的时间线。数据源可以被反复增删，这就是一个随操作次数
     * 增长的指标基数泄漏。
     *
     * "到底是哪个数据源在熔断"这个问题，看日志更合适（那里有 dataSourceId）；
     * 指标只需要回答"现在有没有东西在熔断"。
     */

    /** 数据源缓存命中 / 未命中，两者相除就是命中率。 */
    public static final String CACHE_HIT = "datasource.cache.hit";
    public static final String CACHE_MISS = "datasource.cache.miss";

    /**
     * Redis 不可用导致的<b>降级</b>次数，按原因分标签。
     *
     * <p>这是"Redis 挂了"这件事<b>唯一可靠的观测点</b>。为什么不能靠日志：
     * 降级路径在热路径上（每个请求都要查黑名单和缓存），
     * 实测 Redis 停掉后打 20 个请求、日志涨了 505 行，其中 458 行是异常栈帧（91%）。
     * <b>按 ERROR 级别接告警的话，Redis 一抖就是告警风暴。</b>
     *
     * <p>所以降级日志改成"WARN + 按原因限流（30 秒一条）"，
     * 而"到底降级了多少次"交给这个计数器 —— 它对日志量不敏感，可以放心告警。
     */
    public static final String REDIS_DEGRADATION = "redis.degradation";

    private static final String TAG_REASON = "reason";

    /** 同一个降级原因，最短多久打一条日志。 */
    private static final long DEGRADATION_LOG_INTERVAL_MS = 30_000;

    /** 每个降级原因上次打日志的时间戳。原因只有固定几个，不会无限增长。 */
    private final Map<String, AtomicLong> lastDegradationLogAt = new ConcurrentHashMap<>();

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

    /**
     * 记录一次被并发闸门拒绝。
     *
     * @param reason {@code per-user} / {@code per-data-source}
     */
    public void recordQueryRejected(String reason) {
        registry.counter(QUERY_REJECTED, TAG_REASON, reason).increment();
    }

    /** 队列当前的消息条数，按队列名分标签。 */
    public static final String QUEUE_DEPTH = "mq.queue.depth";

    /**
     * 把一个队列的深度注册成 Gauge。
     *
     * <p>⚠️ 用 <b>Gauge + Supplier</b> 而不是"定时任务读一次再写进计数器"：
     * Gauge 的值是<b>抓取时才算</b>的，反映的是"此刻队列里有多少条"；
     * 定时任务会把读数变成"上一次任务运行时有多少条"，中间的变化全丢了。
     *
     * <p>Supplier 里<b>绝不能抛异常</b> —— Broker 挂掉时让 Gauge 抛出去，
     * 会让整个 `/actuator/prometheus` 抓取失败：从"少一个指标"变成"所有指标都没了"，
     * 而且是在最需要指标的时候。
     */
    public void registerQueueDepthGauge(String queueName, java.util.function.Supplier<Number> depthSupplier) {
        Gauge.builder(QUEUE_DEPTH, depthSupplier, supplier -> supplier.get().doubleValue())
                .description("队列当前的消息条数")
                .tag("queue", queueName)
                .strongReference(true)
                .register(registry);
    }

    /** 熔断器直接拒绝了这次请求（没有去尝试连接）。 */
    public void recordCircuitRejected() {
        registry.counter(CIRCUIT_REJECTED).increment();
    }

    /** 熔断器打开了（连续失败达到阈值）。 */
    public void recordCircuitOpened() {
        registry.counter(CIRCUIT_OPENED).increment();
    }

    /**
     * 把并发闸门表的大小注册成 Gauge。
     *
     * <p>为什么要暴露一个"内部数据结构有多大"这种指标：
     * {@code QueryConcurrencyGuard} 有一个<b>已知的清理竞态</b>（见它的类注释）——
     * 万一清理失效、表一直涨，那是一个缓慢的内存泄漏，<b>不会有任何报错</b>。
     * 有这个 Gauge，"表在涨"就是一条看得见的曲线，而不是等到 OOM 才知道。
     */
    public void registerConcurrencyGateGauges(Map<Long, ?> dataSourceGates, Map<Long, ?> userGates) {
        Gauge.builder(GUARD_GATES, dataSourceGates, Map::size)
                .description("按数据源的并发闸门条目数")
                .tag("scope", "data-source")
                .strongReference(true)
                .register(registry);

        Gauge.builder(GUARD_GATES, userGates, Map::size)
                .description("按用户的并发闸门条目数")
                .tag("scope", "user")
                .strongReference(true)
                .register(registry);
    }

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    /**
     * 记录一次「因为 Redis 不可用而走的降级路径」。
     *
     * <h3>为什么计数和日志要放在一起</h3>
     *
     * <p>它们本来就是同一件事的两面：<b>计数负责"有多少"，日志负责"长什么样"</b>。
     * 分开写的话，各个调用点会各自决定"用哪个级别、要不要打栈"，结果就是现在这样 ——
     * 同样是 Redis 挂掉引起的降级，有的地方是 ERROR + 全栈、有的地方是 WARN、
     * 还有两处<b>完全静默</b>（连日志都没有）。
     *
     * <h3>日志为什么限流，而且不打异常堆栈</h3>
     *
     * <p>这些路径都在热路径上：<b>每个带 token 的请求都要查一次黑名单</b>，
     * 每次读数据源都要读一次缓存。Redis 一挂，它们每次都进 catch。
     *
     * <p>实测：Redis 停掉后打 20 个请求，日志涨了 <b>505 行</b>，
     * 其中 <b>458 行是异常栈帧（91%）</b>。真实流量下几分钟就能把磁盘写满。
     *
     * <p>而那条堆栈其实<b>不提供任何额外信息</b> ——
     * {@code RedisConnectionFailureException: Unable to connect to Redis} 这句话
     * 本身就把原因说完了，后面的六十行栈帧只是重复。所以这里只记异常类型和 message。
     *
     * @param reason 降级原因（固定几个值，作为指标标签，不要传动态字符串）
     * @param detail 具体是哪个 key / 哪个操作
     * @param cause  原始异常，可以为 null
     */
    public void recordRedisDegradation(String reason, String detail, Exception cause) {
        registry.counter(REDIS_DEGRADATION, TAG_REASON, reason).increment();

        AtomicLong lastLoggedAt = lastDegradationLogAt.computeIfAbsent(reason, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        while (true) {
            long prev = lastLoggedAt.get();
            if (now - prev < DEGRADATION_LOG_INTERVAL_MS) {
                // 这个原因最近已经记过了 —— 只计数、不再打日志
                return;
            }
            // CAS：多个线程同时降级时只有一条能"抢到"这次的日志资格
            if (lastLoggedAt.compareAndSet(prev, now)) {
                break;
            }
        }

        log.warn("Redis 不可用，已按降级路径处理（{} 秒内同一原因只记一条）。reason={}, detail={}, cause={}",
                DEGRADATION_LOG_INTERVAL_MS / 1000, reason, detail,
                cause == null ? "-" : cause.getClass().getSimpleName() + ": " + cause.getMessage());
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
