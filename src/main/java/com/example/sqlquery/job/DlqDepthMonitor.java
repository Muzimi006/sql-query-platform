package com.example.sqlquery.job;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.example.sqlquery.mq.RabbitConfig;

/**
 * 死信队列的可见性。
 *
 * <h2>它补的是一个什么样的洞</h2>
 *
 * <p>项目一开始就配了死信链路（`export.dlx/export.dlq`、`review.dlx/review.dlq`），
 * 拓扑和绑定都对。但盘点之后发现一个很尴尬的事实：
 *
 * <blockquote>
 * <b>消息不会静默消失了，但替代品是一个没有任何机制去读的队列。</b>
 * </blockquote>
 *
 * <ul>
 *   <li>全项目只有两个 `\@RabbitListener`，分别监听 `export.queue` 和 `review.queue` ——
 *       <b>没有人监听死信队列</b>；</li>
 *   <li>DLQ 常量在主代码里只出现在"声明"和"日志字符串"里；</li>
 *   <li>三个 `\@Scheduled`（文件清理、两个补偿任务）<b>都不查队列深度</b>。</li>
 * </ul>
 *
 * <p>所以消息进了死信队列之后，唯一的痕迹是那一条 ERROR 日志 ——
 * 只要没人翻日志，"有任务处理失败"这件事<b>完全不可见</b>。
 *
 * <h2>这个类做了什么、没做什么（必须说清）</h2>
 *
 * <table border="1">
 *   <caption>范围</caption>
 *   <tr><th>做了</th><th>没做</th></tr>
 *   <tr><td>把两个 DLQ 的深度暴露成 Gauge `mq_queue_depth{queue=...}`</td>
 *       <td><b>没有消费者去处理 DLQ 里的消息</b>（不做自动重放）</td></tr>
 *   <tr><td>非空时定时打 ERROR 日志</td>
 *       <td><b>没有告警规则</b>（要接 Prometheus + Alertmanager 才有意义）</td></tr>
 *   <tr><td>"读不到"与"是空的"分开：读失败计数 + 新鲜度 Gauge</td>
 *       <td>告警规则本身仍要人配</td></tr>
 * </table>
 *
 * <p><b>为什么不做自动重放</b>：进了死信的是"处理失败<b>且结果未能落库</b>"的消息，
 * 它对应的业务状态是<b>未知</b>的（工单可能停在 `EXECUTING`、导出可能停在 `RUNNING`）。
 * 自动重放等于"把一个自己都说不清状态的操作再执行一遍"——而这里面<b>有写语句</b>。
 * 正确做法是先把"有多少、是哪些"变得可见，由人判断之后再决定重放还是标失败。
 *
 * <h2>⚠️ 深度为什么是"缓存快照"而不是"抓取时现查"</h2>
 *
 * <p>第一版是把"现查 Broker"直接放进 Gauge 的 Supplier 里。它的好处是读数永远是最新的，
 * 但代价在故障时才暴露：<b>Gauge 的 Supplier 是在 Prometheus 抓取线程上执行的</b>，
 * 抓取会同步等一次 AMQP 请求-应答（连接断了还要等重连），两个队列叠加起来，
 * 很容易超过 Prometheus 的抓取超时 —— 于是<b>故障期间恰好丢掉了全部指标</b>，
 * 包括那些本来能说明"是 Broker 挂了"的指标。
 *
 * <p>现在改成：定时刷新把读数写进快照，Gauge 只读快照（纯内存、不阻塞、不抛异常）。
 * 代价是<b>最多滞后一个刷新周期</b>（`mq.dlq-depth-refresh-ms`，默认 15 秒）——
 * 对一个"偶尔来一条、要人来看"的死信队列来说，15 秒的滞后没有影响。
 *
 * <p>滞后这件事本身也必须可见，否则"深度是 0"就有两种解释（真空了 / 读不到）。
 * 所以同时暴露：
 * <ul>
 *   <li>`mq_queue_depth_age_seconds{queue=...}`：距上次<b>成功</b>读取的秒数，
 *       超过刷新周期就说明读数已经失效；</li>
 *   <li>`mq_dlq_read_failed_total{queue=...}`：读取失败次数，直接说明"读不到"。</li>
 * </ul>
 */
@Slf4j
@Component
public class DlqDepthMonitor {

    /** 要盯的死信队列。 */
    private static final List<String> WATCHED_QUEUES = List.of(
            RabbitConfig.EXPORT_DLQ,
            RabbitConfig.REVIEW_DLQ);

    private final RabbitTemplate rabbitTemplate;

    private final SqlPlatformMetrics metrics;

    /** 最近一次成功读取到的深度。Gauge 读的就是它。 */
    private final Map<String, AtomicLong> cachedDepth = new ConcurrentHashMap<>();

    /** 最近一次成功读取的时间戳（毫秒）。用来算新鲜度。 */
    private final Map<String, AtomicLong> lastSuccessAt = new ConcurrentHashMap<>();

    public DlqDepthMonitor(RabbitTemplate rabbitTemplate, SqlPlatformMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;

        long now = System.currentTimeMillis();
        for (String queue : WATCHED_QUEUES) {
            AtomicLong depth = new AtomicLong(0);
            AtomicLong successAt = new AtomicLong(now);
            cachedDepth.put(queue, depth);
            lastSuccessAt.put(queue, successAt);

            // ⚠️ 这两个 Supplier 必须是纯内存读取：它们在抓取线程上执行，
            //    不许发 AMQP 请求、不许抛异常（抛出去会让整个抓取失败）
            metrics.registerQueueDepthGauge(queue, depth::get);
            metrics.registerQueueDepthAgeGauge(queue,
                    () -> Math.max(0, (System.currentTimeMillis() - successAt.get()) / 1000));
        }
    }

    /**
     * 刷新深度快照。
     *
     * <p>放在定时任务里而不是抓取路径上：读一次要发一次 AMQP 请求，
     * 而 Prometheus 是按固定间隔来抓的 —— 让抓取去等 Broker，
     * 等于把"监控"变成了"监控对象的依赖"。
     */
    @Scheduled(fixedDelayString = "${mq.dlq-depth-refresh-ms:15000}")
    public void refreshDepths() {
        for (String queue : WATCHED_QUEUES) {
            Long depth = readDepth(queue);
            if (depth == null) {
                // 读不到：保留上一次的读数，只把"读不到"这件事记下来。
                // 清零会更糟 —— 那等于把"未知"显示成"没问题"
                metrics.recordDlqDepthReadFailed(queue);
                continue;
            }
            cachedDepth.get(queue).set(depth);
            lastSuccessAt.get(queue).set(System.currentTimeMillis());
        }
    }

    /**
     * 读队列深度；<b>失败返回 null</b>（而不是 0）。
     *
     * <p>"读不到"和"是空的"是两件事，混成 0 会让 Broker 挂掉看起来像"队列正常"。
     *
     * <h3>⚠️ 为什么用 {@code queueDeclarePassive} 而不是 {@code RabbitAdmin.getQueueInfo}</h3>
     *
     * <p>第一版写的是 {@code RabbitAdmin}，结果**所有集成测试的上下文都起不来**：
     *
     * <pre>
     * No qualifying bean of type 'org.springframework.amqp.rabbit.core.RabbitAdmin' available
     * </pre>
     *
     * <p>项目里从来没有人注入过 {@code RabbitAdmin}，所以这个缺失一直没暴露 ——
     * 声明队列靠的是 Spring AMQP 内部自己的机制。换到 Boot 4 之后它不再是
     * 一个可以直接注入的 Bean 了。
     *
     * <p>改成走 {@code RabbitTemplate} 之后不依赖任何额外的 Bean，
     * 而且 <b>AMQP 的 {@code queue.declare(passive=true)} 的返回值里本来就带消息条数</b> ——
     * 比"查队列信息对象再取字段"更直接。
     *
     * <p>队列不存在时会抛 {@code IOException}（404 NOT_FOUND），照样算读失败。
     */
    private Long readDepth(String queue) {
        try {
            Long count = rabbitTemplate.execute(channel ->
                    // passive=true：只查询、不创建。队列不存在时抛 404
                    (long) channel.queueDeclarePassive(queue).getMessageCount());
            return count == null ? null : count;
        } catch (Exception e) {
            // 不在这里打日志：Broker 挂掉时这个定时任务每 15 秒走一次，
            // 每次都打一条会刷屏，而"读不到"已经由计数器 + 新鲜度指标表达
            return null;
        }
    }

    /**
     * 定时把"非空"这件事打进日志。
     *
     * <p>指标要接了告警才有用；日志是给"还没接告警"的环境兜底的 ——
     * 而绝大多数环境都还没接。
     *
     * <p>这里<b>先刷新一次快照再读</b>：这条路径十分钟才走一次，
     * 同步读一次 Broker 的成本可以忽略，换来的是"日志里说的数字是此刻的"。
     * （Gauge 那边不能这么做 —— 那是每次抓取都走的高频路径。）
     */
    @Scheduled(cron = "${mq.dlq-check-cron:0 */10 * * * ?}")
    public void reportNonEmptyDlq() {
        refreshDepths();
        for (String queue : WATCHED_QUEUES) {
            long depth = cachedDepth.get(queue).get();
            if (depth > 0) {
                log.error("死信队列 {} 里有 {} 条消息 —— 说明有任务被处理失败、且结果未能落库，"
                                + "需要人工查看（本系统不会自动重放：那些消息对应的业务状态是未知的）。",
                        queue, depth);
            }
        }
    }
}
