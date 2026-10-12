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
 *   <tr><td>限流触发次数</td><td>{@code sql.query.rate_limited}（带 scope 标签）</td></tr>
 * </table>
 */
@Slf4j
@Component
public class SqlPlatformMetrics {

    /** 用户 SQL 的执行耗时，按结果分标签。 */
    public static final String QUERY_DURATION = "sql.query.duration";

    /** 结果被截断的次数（超过单次返回上限）。 */
    public static final String QUERY_TRUNCATED = "sql.query.truncated";

    /**
     * 被<b>频率</b>限流拦下的次数，用 {@code scope} 标签区分是哪一道限流。
     *
     * <p>⚠️ 名字里的 {@code query} 是历史遗留：它最初只统计"查询执行"，
     * 现在登录、注册、工单提交、NL2SQL、测试连接也往这里记。
     * 保留旧名字是为了不打断已有的看板与告警规则，<b>要区分就看 scope</b>。
     */
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

    /**
     * 限流作用域标签。
     *
     * <p>⚠️ 取值只能来自固定的几个常量，<b>绝不能把限流 key 拼进去</b>：
     * key 里有 userId、IP、用户名，那样每来一个用户就多一条时间线 ——
     * 指标基数随用户数增长，是 Prometheus 最常见的自伤方式。
     */
    private static final String TAG_SCOPE = "scope";

    /** 同一个降级原因，最短多久打一条日志。 */
    private static final long DEGRADATION_LOG_INTERVAL_MS = 30_000;

    /** 每个降级原因上次打日志的时间戳。原因只有固定几个，不会无限增长。 */
    private final Map<String, AtomicLong> lastDegradationLogAt = new ConcurrentHashMap<>();

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_DATA_SOURCE = "dataSourceId";
    private static final String TAG_LINK = "link";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    private final Counter queryTruncated;
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

    /**
     * 记录一次被频率限流拦下。
     *
     * @param scope 哪一道限流，取值固定：{@code login-ip} / {@code login-account} /
     *              {@code register-ip} / {@code query} / {@code sql-review} /
     *              {@code nl2sql} / {@code datasource-test}
     */
    public void recordRateLimited(String scope) {
        registry.counter(QUERY_RATE_LIMITED, TAG_SCOPE, scope).increment();
    }

    /**
     * 死信队列深度<b>读取失败</b>的次数，按队列分标签。
     *
     * <p>补的是原先的一个有损之处：以前读不到和"队列是空的"都被合并成 0，
     * 于是 Broker 挂掉时指标上看起来"一切正常"。现在读失败会额外记一个计数，
     * 配合 {@link #QUEUE_DEPTH_AGE}（新鲜度）就能把"读不到"和"是 0"分开。
     */
    public static final String DLQ_READ_FAILED = "mq.dlq.read_failed";

    public void recordDlqDepthReadFailed(String queueName) {
        registry.counter(DLQ_READ_FAILED, "queue", queueName).increment();
    }

    /**
     * 队列深度指标的<b>新鲜度</b>：距离上一次成功读取过去了多少秒。
     *
     * <p>为什么必须单独有一个指标：深度改成缓存之后，"深度是 0"就有了两种解释 ——
     * 队列真的空了，或者<b>根本读不到</b>。age 超过刷新间隔就说明是后者。
     */
    public static final String QUEUE_DEPTH_AGE = "mq.queue.depth.age_seconds";

    public void registerQueueDepthAgeGauge(String queueName,
                                           java.util.function.Supplier<Number> ageSupplier) {
        Gauge.builder(QUEUE_DEPTH_AGE, ageSupplier, supplier -> supplier.get().doubleValue())
                .description("距上次成功读取队列深度的秒数（超过刷新间隔 = 读数已失效）")
                .tag("queue", queueName)
                .strongReference(true)
                .register(registry);
    }

    /**
     * Redis 失效广播订阅是否活着：1 = 已订阅且容器在跑，0 = 没订阅或已停。
     *
     * <p>这个 Gauge 存在的理由：订阅失败/掉线以前<b>只在启动日志里说一次</b>，
     * 之后完全不可见。多实例部署时表现为"改了数据源，另一台机器上的池子还是旧的"，
     * 而这条线上没有任何一个数字在变化。
     */
    public static final String REDIS_SUBSCRIBER_UP = "redis.subscriber.up";

    public void registerRedisSubscriberUpGauge(java.util.function.Supplier<Number> upSupplier) {
        Gauge.builder(REDIS_SUBSCRIBER_UP, upSupplier, supplier -> supplier.get().doubleValue())
                .description("Redis 失效广播订阅是否在线（1 在线 / 0 离线）")
                .strongReference(true)
                .register(registry);
    }

    /** Redis 订阅被看门狗发现掉线并重启的次数。长期为 0 才是正常的。 */
    public static final String REDIS_SUBSCRIBER_RESTART = "redis.subscriber.restart";

    public void recordRedisSubscriberRestart() {
        registry.counter(REDIS_SUBSCRIBER_RESTART).increment();
    }

    /**
     * 被 NL2SQL 的并发上限拦下的次数。
     *
     * <p>⚠️ 它和 {@code scope=nl2sql} 的限流是两件事：那个是"你一分钟点了太多次"，
     * 这个是"同时在跑的生成任务已经占满了"。后者说明<b>该扩线程或该排队</b>了。
     */
    public static final String NL2SQL_REJECTED = "sql.nl2sql.rejected";

    public void recordNl2sqlRejected() {
        registry.counter(NL2SQL_REJECTED).increment();
    }

    /**
     * 保留策略清理掉的行数，按表分标签。
     *
     * <p>⚠️ 为什么清理<b>必须</b>有指标：删除是不可逆的，"昨天删了多少"是事后唯一能查的东西。
     * 一条不动的曲线同样有意义 —— 它说明"该清理的没被清"（表会一直涨）。
     */
    public static final String RETENTION_DELETED = "retention.deleted";

    public void recordRetentionDeleted(String target, int rows) {
        registry.counter(RETENTION_DELETED, "target", target).increment(rows);
    }

    /** 保留策略某一项失败的次数（查询或删除抛异常）。持续大于 0 = 表在无界增长。 */
    public static final String RETENTION_FAILED = "retention.failed";

    /**
     * 回执（ack / nack）**本身失败**的次数，按链路分标签。
     *
     * <p>为什么它必须单独计数：ack 失败以前会被消费者的 catch 当成「业务处理失败」，
     * 于是<b>一次成功的执行被报成失败</b>（工单侧会 nack 进死信、导出侧会重跑一遍）。
     * 现在 ack 失败只记这个计数器 —— 消息没被确认，Broker 自然会重投，
     * 而重投由幂等认领挡住重复执行。<b>这才是它的正确恢复路径。</b>
     */
    public static final String ACK_FAILED = "mq.ack.failed";

    /**
     * 平台库写的**合并提交**次数，以及每次提交带上了几条写。
     *
     * <p>这两个计数器是「合并写真的生效了」唯一可靠的证据：
     * 一次查询请求应当表现为 <b>flush +1 / items +2</b>（审计 + 历史在同一个事务里）。
     *
     * <p>⚠️ 为什么不用 MySQL 的 `Com_commit` 来量：它只统计**客户端显式发的 COMMIT**，
     * 自动提交下的单条 INSERT 是**隐式提交**、不计入 —— 于是「合并了」和「没合并」在它眼里都是 1。
     * 而 `Handler_commit` 虽然是引擎内部计数，但它**是全局的**，会被其它线程的提交干扰
     * （实测同一次请求在两个状态下分别是 1 和 7，噪声太大）。
     */
    public static final String WRITE_FLUSH = "platform.write.flush";

    /** 被合并提交的写条数（按成败分）。 */
    public static final String WRITE_FLUSHED_ITEMS = "platform.write.flushed";

    /**
     * 登录时把老哈希升级到当前成本因子的次数（成功 / 失败）。
     *
     * <p>它回答一个很具体的问题：**「库里还有多少老哈希没升级完」**。
     * 调高 `platform.password.bcrypt-strength` 之后，老哈希只会在用户**下次登录**时被升级，
     * 所以升级是**渐进**的 —— 这两个计数器就是它的进度条。
     *
     * <p>⚠️ 失败计数尤其要看：升级失败不阻断登录（有意），
     * 但「一直失败」意味着成本因子永远只对新用户生效。
     */
    public static final String PASSWORD_REHASH = "password.rehash";

    public void recordPasswordRehashed() {
        registry.counter(PASSWORD_REHASH, TAG_OUTCOME, OUTCOME_SUCCESS).increment();
    }

    public void recordPasswordRehashFailed() {
        registry.counter(PASSWORD_REHASH, TAG_OUTCOME, OUTCOME_FAILED).increment();
    }

    public void recordWriteFlush(int items, int succeeded) {
        registry.counter(WRITE_FLUSH).increment();
        registry.counter(WRITE_FLUSHED_ITEMS, TAG_OUTCOME, OUTCOME_SUCCESS).increment(succeeded);
        if (items > succeeded) {
            registry.counter(WRITE_FLUSHED_ITEMS, TAG_OUTCOME, OUTCOME_FAILED)
                    .increment(items - succeeded);
        }
    }

    public void recordAckFailed(String link) {
        registry.counter(ACK_FAILED, TAG_LINK, link).increment();
    }

    /**
     * 投递**没能拿到确认**的次数，按链路分标签（自己等确认的那几条路径记的）。
     *
     * <p>它和 {@link #PUBLISH_FAILED} 的分工：那个是<b>Broker 的否定确认回调</b>记的
     * （消息确实被拒了），这个是<b>调用方等确认超时或拿到否定</b>时记的
     * （我们自己的判断）。两者同时增长说明同一次投递既被拒又被我们发现，属正常。
     */
    public static final String PUBLISH_UNCONFIRMED = "mq.publish.unconfirmed";

    public void recordPublishUnconfirmed(String link) {
        registry.counter(PUBLISH_UNCONFIRMED, TAG_LINK, link).increment();
    }

    public void recordRetentionFailed(String target) {
        registry.counter(RETENTION_FAILED, "target", target).increment();
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
     * <p>⚠️ Supplier 里<b>绝不能抛异常</b>，也<b>绝不能阻塞</b> —— 它是在 Prometheus 抓取线程上执行的。
     * Broker 挂掉时让 Gauge 抛出去，会让整个 {@code /actuator/prometheus} 抓取失败：
     * 从"少一个指标"变成"所有指标都没了"，而且是在最需要指标的时候。
     *
     * <p><b>所以这里传进来的 Supplier 必须是"读一个缓存快照"，不许直接发 AMQP 请求</b>：
     * 早先的实现是抓取时现查 Broker，Broker 不可达时每次抓取都要等应答超时（2 个队列 × N 秒），
     * Prometheus 会直接把抓取判成超时 —— 于是故障期间恰好丢掉了全部指标。
     * 快照由 {@code DlqDepthMonitor} 的定时刷新维护，新鲜度看 {@link #QUEUE_DEPTH_AGE}。
     */
    public void registerQueueDepthGauge(String queueName, java.util.function.Supplier<Number> depthSupplier) {
        Gauge.builder(QUEUE_DEPTH, depthSupplier, supplier -> supplier.get().doubleValue())
                .description("队列当前的消息条数")
                .tag("queue", queueName)
                .strongReference(true)
                .register(registry);
    }

    /** 消费者因**基础设施故障**重试了一次投递，按队列名分标签。 */
    public static final String CONSUMER_RETRY = "mq.consumer.retry";

    /**
     * 记录一次消费重试。
     *
     * <p>为什么要单独计数而不是只看日志：重试是**有代价的** ——
     * 它把消费者线程按在 {@code sleep} 上，而每个队列的消费者是有限的
     * （{@code prefetch: 1} + 单消费者）。重试次数悄悄涨上去，
     * 表现出来是"消息处理变慢了"，而不是任何一条报错。
     *
     * <p>这个计数器连续增长 = 下游在抖（数据库连不上、网络不通）。
     * 它**长期为 0 才是正常的**。
     */
    public void recordConsumerRetry(String queueName) {
        registry.counter(CONSUMER_RETRY, "queue", queueName).increment();
    }

    /**
     * **生产者侧**投递失败的次数，按链路分标签。
     *
     * <h2>为什么必须有这个计数器</h2>
     *
     * <p>在这之前，"投递失败"唯一的痕迹是一条 ERROR 日志。而两个业务侧对它的处置完全相反：
     *
     * <ul>
     *   <li><b>工单链路</b>：吞掉异常、让审批接口正常返回，工单停在 APPROVED，
     *       由 {@code SqlReviewRecoveryJob} 补偿重投；</li>
     *   <li><b>导出链路</b>：立刻把任务标 FAILED 并给用户一句能看懂的话
     *       （导出没有补偿重投 —— 重跑会覆盖同一个文件名，中途中断留下的半成品
     *       和重跑的结果无法区分）。</li>
     * </ul>
     *
     * <p>两种处置都对，但**共同点是失败之后系统仍然"看起来正常"**：
     * 一个接口返回 200、一个返回明确的业务提示，队列里就是少了一条消息。
     * 没有计数器的话，"Broker 出问题了"这件事只能靠人翻日志发现 ——
     * 而这个项目里已经有两次"日志里写了但没人看"的教训。
     *
     * <p>⚠️ 它和 publisher confirm 回调（{@code RabbitConfirmConfig}）**不是一回事**：
     * 这里记的是"调用就抛了异常"（连接不上、channel 关闭），
     * 回调处理的是"调用成功了但 Broker 后来否认"（异步）。两者互补，都要。
     */
    public static final String PUBLISH_FAILED = "mq.publish.failed";

    /** 投递失败的链路标签值。用常量而不是字符串字面量，避免指标和告警规则对不上。 */
    public static final String LINK_EXPORT = "export";

    public static final String LINK_REVIEW = "review";

    /**
     * 记录一次生产者侧投递失败。
     *
     * @param link {@link #LINK_EXPORT} 或 {@link #LINK_REVIEW}
     */
    public void recordPublishFailure(String link) {
        registry.counter(PUBLISH_FAILED, TAG_LINK, link).increment();
    }

    /**
     * 工单执行结果<b>没能留存</b>（前镜像 / 反向 SQL 没写进库）的次数。
     *
     * <p>这个计数器存在的理由：那条路径上**业务库的写已经提交了**，
     * 而回滚依据只活在一条 ERROR 日志里 —— 日志轮转之后，
     * 「这条工单改了什么、能不能回滚」就再也没有依据了。
     *
     * <p>所以它必须可告警：**持续大于 0 就说明有一批工单执行过、却没有回滚依据。**
     */
    public static final String UNDO_PERSIST_FAILED = "review.undo.persist.failed";

    public void recordUndoPersistFailed() {
        registry.counter(UNDO_PERSIST_FAILED).increment();
    }

    /**
     * ⭐ **执行结果写回被拒**的次数（条件更新影响 0 行），按链路分标签。
     *
     * <p>这是"**数据已经改了，但状态没写回去**"<b>唯一</b>的信号。
     * 触发它的正常情况是补偿任务抢先（竞态），不正常的情况是阈值/重试窗口错配 ——
     * 但两种情况在日志里长得一模一样，只有计数器能告诉你"有几条结果被丢弃了"。
     */
    public static final String RESULT_WRITEBACK_DROPPED = "result.writeback.dropped";

    public void recordResultDropped(String link) {
        registry.counter(RESULT_WRITEBACK_DROPPED, TAG_LINK, link).increment();
    }

    /**
     * 查询历史写入失败次数。
     *
     * <p>这条路径**不能影响应答**（语句可能已经生效了），
     * 所以失败必须靠这个计数器被发现，而不是靠用户看到报错。
     */
    public static final String HISTORY_SAVE_FAILED = "query.history.save.failed";

    public void recordHistorySaveFailed() {
        registry.counter(HISTORY_SAVE_FAILED).increment();
    }

    /**
     * 审计日志写入失败次数。
     *
     * <p>审计写入失败会造出一个**无声的安全审计缺口**（业务照样成功、审计记录没了），
     * 而它以前只有一条 ERROR 日志。
     */
    public static final String AUDIT_WRITE_FAILED = "audit.write.failed";

    /**
     * **没有写**审计日志的次数，按原因分标签（不是失败，是有意不写）。
     *
     * <p>它补的是 G-07：被并发闸门 / 熔断拒绝的请求**一个资源都没碰到**，
     * 却因为审计切面是 {@code @Before} 而往主库插了一条记录 ——
     * 压测实测的后果是负向扩展（并发 8 → 64 时成功吞吐 296.5 → 87.5 QPS）。
     *
     * <p>⚠️ 「不写」必须留计数：否则「审计少了一条」和「这次请求本来就没做事」
     * 在指标上完全一样，事后无法分辨。
     */
    public static final String AUDIT_SKIPPED = "audit.skipped";

    public void recordAuditSkipped(String reason) {
        registry.counter(AUDIT_SKIPPED, TAG_REASON, reason).increment();
    }

    public void recordAuditWriteFailed() {
        registry.counter(AUDIT_WRITE_FAILED).increment();
    }

    /**
     * 消息被 Broker **退回**（路由不到任何队列）的次数。
     *
     * <p>⚠️ 它和 {@link #PUBLISH_FAILED} 是两回事：
     * 那个记的是"调用同步抛异常"，这个记的是"调用成功了、但消息没路由到任何队列"。
     * 两个都要有，否则"消息根本没进 Broker"这件事只活在日志里。
     */
    public static final String MESSAGE_RETURNED = "mq.message.returned";

    public void recordReturnedMessage() {
        registry.counter(MESSAGE_RETURNED).increment();
    }

    /**
     * 补偿任务处置失败的次数，按链路分标签。
     *
     * <p>"一批导出被补偿判失败""工单被放弃重投"以前只有 WARN 日志，
     * 批量发生时（事故后）根本没人能立刻知道规模。
     */
    public static final String RECOVERY_FAILED = "recovery.failed";

    public void recordRecoveryFailed(String link) {
        registry.counter(RECOVERY_FAILED, TAG_LINK, link).increment();
    }

    /**
     * 因**配置变化**而重建连接池的次数。
     *
     * <p>它持续大于 0 说明"失效广播没能送达"（或者数据源被频繁修改）——
     * 这条路径以前是**静默的**：池子用旧凭据一直连不上，而 Redis 恢复后也不会自愈。
     */
    public static final String POOL_REBUILT_ON_CONFIG_CHANGE = "datasource.pool.rebuilt";

    public void recordPoolRebuiltOnConfigChange() {
        registry.counter(POOL_REBUILT_ON_CONFIG_CHANGE).increment();
    }

    /**
     * **在途任务的消息被重投、又被幂等保护 ack 掉**的次数，按链路分标签。
     *
     * <p>听起来像"重复投递被正确挡住了"，其实是 <b>一次真实的消息丢弃</b>：
     * 进程在任务执行到一半时被 kill（或滚动发布），Broker 重投，
     * 而重投时状态已经不是 PENDING/APPROVED 了 → 认领失败 → <b>消息被 ack 掉</b>，
     * 任务只能等补偿任务判失败（导出 10 分钟 / 工单 15 分钟）。
     *
     * <p>它持续大于 0 说明**这个服务在非正常停机**（kill -9 / OOM / 滚动发布没等优雅停机）。
     */
    public static final String REDELIVERY_SKIPPED_IN_FLIGHT = "mq.redelivery.inflight";

    public void recordRedeliverySkippedInFlight(String link) {
        registry.counter(REDELIVERY_SKIPPED_IN_FLIGHT, TAG_LINK, link).increment();
    }

    /**
     * 熔断器**恢复**的次数。
     *
     * <p>补它的理由很直接：{@code datasource.circuit.opened} 是单调递增的，
     * 只有 opened 没有 recovered 的话，看板上分不清"已经恢复了"和"还在熔断中"。
     */
    public static final String CIRCUIT_RECOVERED = "datasource.circuit.recovered";

    public void recordCircuitRecovered() {
        registry.counter(CIRCUIT_RECOVERED).increment();
    }

    /** "系统异常"（全局兜底）的次数。 */
    public static final String SYSTEM_ERROR = "system.error";

    /**
     * 走到全局兜底的未预期异常。
     *
     * <p>⚠️ 为什么要计数：这个项目的错误响应**一律是 HTTP 200 + body 里的 code**，
     * 所以"服务端炸了"在 `http_server_requests{status=...}` 上<b>看不出来</b> ——
     * 任何按 HTTP 状态算错误率的告警（网关、APM、K8s）对本项目都是瞎的。
     * 这个计数器是那条链路上唯一可告警的信号。
     */
    public void recordSystemError() {
        registry.counter(SYSTEM_ERROR).increment();
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
