package com.example.sqlquery.cache;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据源失效广播的订阅方：负责订阅容器的<b>启动与重试</b>。
 *
 * <h2>为什么不把容器交给 Spring 当 bean</h2>
 *
 * <p>{@code RedisMessageListenerContainer} 是 {@code SmartLifecycle}。
 * 作为 bean 时 Spring 会在容器刷新阶段调它的 {@code start()}，
 * 而 {@code start()} 内部会<b>同步连一次 Redis</b>，连不上就抛异常、
 * 中断整个刷新流程。<b>实测：Redis 没起来时应用直接启动失败。</b>
 *
 * <p>这与项目已经确立的取向冲突：黑名单、限流、缓存三处在 Redis 故障时都选择
 * "降级放行"，没有理由让"广播监听"这一个非核心能力反过来决定应用的生死。
 *
 * <h2>每次重试都新建容器实例（这是第二版才修对的）</h2>
 *
 * <p>第一版是"复用一个容器实例，失败后反复调 {@code start()}"，
 * 实测结果是<b>最难发现的那种假成功</b>：
 * 日志明确打印了"订阅容器已启动"，但 {@code PUBSUB NUMSUB} 查出来的订阅数仍然是 0 ——
 * 容器在第一次失败时内部状态已经半启动（{@code running} 被置位、
 * 订阅任务也已创建），之后 {@code start()} 会直接短路返回，既不再连 Redis 也不报错。
 *
 * <p>所以重试时<b>丢弃旧实例、新建一个</b>：新实例没有历史状态，
 * {@code start()} 会老老实实去连 Redis。代价是多创建几个对象，
 * 而这条路径本来就只有 Redis 长时间不可用时才会走到。
 *
 * <h2>为什么不能拿 {@code isRunning()} 当判断依据</h2>
 *
 * <p>同一版里踩的另一个坑：{@code start()} 是<b>先置位 {@code running}、
 * 再连 Redis</b> 的。所以连接失败抛异常时，{@code isRunning()} 已经返回 {@code true}。
 * 用它判断"是否订阅成功"，重试会永远被跳过。这里维护自己的 {@link #subscribed} 标记，
 * 只在 {@code start()} 正常返回后才置位。

 * <h2>看门狗：订阅成功之后也要能被发现"它死了"</h2>
 *
 * <p>{@link #subscribed} 只表达"曾经订阅成功"。容器在运行中被判定死亡时它不会变，
 * 于是重试逻辑再也进不来 —— 这是一个<b>只有"永不重启的进程"才会遇到</b>的静默故障。
 * 所以每次重试调度同时兼作看门狗：已订阅但容器不在运行时，
 * 记一条 ERROR、埋一个计数器（{@code redis.subscriber.restart}）并重建订阅。
 * 当前状态本身也暴露成 Gauge（{@code redis.subscriber.up}）。
 */
@Slf4j
@Component
public class RedisSubscriptionStarter {

    private final RedisConnectionFactory connectionFactory;
    private final DbSourceCacheInvalidationListener listener;
    private final SqlPlatformMetrics metrics;

    /** 与重试调度共用同一个配置项，避免"日志说的时间"和"实际重试的时间"对不上。 */
    @Value("${redis.subscriber-retry-ms:30000}")
    private long retryIntervalMs;

    /**
     * 是否已经成功订阅。
     *
     * <p>{@code volatile} 是必需的：写在启动/重试线程，读在调度线程，
     * 没有它调度线程可能一直读到旧值。
     */
    private volatile boolean subscribed = false;

    /** 当前生效的容器；未订阅成功时为 {@code null}。 */
    private volatile RedisMessageListenerContainer container;

    public RedisSubscriptionStarter(RedisConnectionFactory connectionFactory,
                                    DbSourceCacheInvalidationListener listener,
                                    SqlPlatformMetrics metrics) {
        this.connectionFactory = connectionFactory;
        this.listener = listener;
        this.metrics = metrics;

        // 订阅状态必须是**一条能看见的曲线**，不能只在启动日志里说一次：
        // 订阅失败的后果是多实例部署时数据源失效广播收不到 ——
        // 表现是"A 机器改了数据源，B 机器的连接池还是旧的"，
        // 而这期间没有任何一条日志、任何一个数字在变化。
        metrics.registerRedisSubscriberUpGauge(this::isSubscribed);
    }

    /** 1 = 已订阅且连接容器在跑；0 = 没订阅或容器已停。 */
    private int isSubscribed() {
        RedisMessageListenerContainer current = this.container;
        return subscribed && current != null && current.isRunning() ? 1 : 0;
    }

    /** 应用就绪后再启动订阅：此时 Web 层已能对外服务，订阅晚几百毫秒不影响正确性。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startWhenReady() {
        tryStart();
    }

    /**
     * 兜底重试：覆盖"应用先起、Redis 后起"的情况。
     *
     * <p>订阅成功之后这个方法就只是空转，不再打扰容器 ——
     * 后续如果连接断了，容器自己的 {@code recoveryInterval} 机制负责重连，
     * 那是它擅长的部分，不需要在这里重复实现。
     */
    @Scheduled(fixedDelayString = "${redis.subscriber-retry-ms:30000}",
            initialDelayString = "${redis.subscriber-retry-ms:30000}")
    public void retryIfNotSubscribed() {
        if (subscribed) {
            // ── 看门狗：订阅成功之后，这里不再重试，但**必须发现"它后来又死了"** ──
            //
            // 以前这段是直接 return 的，于是有一个没人管的空洞：
            // 容器在运行中被判定死亡（连接彻底断开且重连无望）时，
            // subscribed 仍然是 true，重试永远被跳过 —— 失效广播就这么静默地停了，
            // 直到进程重启为止。
            //
            // 为什么这里可以用 isRunning()：类注释里说过它**不能**用来判断"订阅成功"
            // （start() 会先置位再连 Redis，连失败时它也是 true）。
            // 但反过来是成立的：isRunning() == false 就一定没在跑。
            // 作为一个"死亡探测器"，它不会误报（只可能漏报）。
            RedisMessageListenerContainer current = this.container;
            if (current != null && current.isRunning()) {
                return;
            }

            log.error("Redis 订阅容器已经不在运行（原来标记为已订阅），失效广播在此期间收不到 —— "
                    + "多实例部署时连接池可能不同步。现在重建订阅。");
            metrics.recordRedisSubscriberRestart();
            // 先销毁再改状态：destroyQuietly 里的 stop() 会再触发一次状态变化，
            // 顺序反了会留下一个"已销毁但仍然标记为已订阅"的窗口
            destroyQuietly(current);
            this.container = null;
            this.subscribed = false;
        }
        log.warn("Redis 订阅尚未成功，重试中（间隔 {}ms）", retryIntervalMs);
        tryStart();
    }

    private synchronized void tryStart() {
        if (subscribed) {
            return;
        }

        // 每次都新建：复用半启动的实例会"假装成功"（见类注释）
        RedisMessageListenerContainer candidate = new RedisMessageListenerContainer();
        candidate.setConnectionFactory(connectionFactory);
        candidate.addMessageListener(listener,
                new ChannelTopic(DbSourceCacheInvalidator.INVALIDATION_CHANNEL));

        try {
            candidate.afterPropertiesSet();
            candidate.start();
            this.container = candidate;
            this.subscribed = true;
            log.info("Redis 订阅已建立，开始接收数据源失效广播。channel={}",
                    DbSourceCacheInvalidator.INVALIDATION_CHANNEL);
        } catch (Exception e) {
            // 失败的这个实例要销毁掉，否则它会自己留在后台重连，
            // 下一个候选实例又建一条连接，最终连了几条没人说得清
            destroyQuietly(candidate);
            log.warn("Redis 订阅启动失败，{}ms 后重试。此期间本实例收不到失效广播，"
                            + "多实例部署时连接池可能不同步（单实例部署无影响）",
                    retryIntervalMs, e);
        }
    }

    /** 应用关闭时停掉订阅，避免进程退出时留下悬挂的连接与线程。 */
    @PreDestroy
    public void stop() {
        RedisMessageListenerContainer current = this.container;
        if (current != null) {
            destroyQuietly(current);
            this.container = null;
            this.subscribed = false;
        }
    }

    private void destroyQuietly(RedisMessageListenerContainer target) {
        try {
            target.stop();
            target.destroy();
        } catch (Exception e) {
            // 销毁失败只影响资源回收，不影响业务正确性
            log.warn("销毁 Redis 订阅容器失败，可能有连接未能及时释放", e);
        }
    }
}
