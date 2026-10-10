package com.example.sqlquery.config;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * </table>
 *
 * <p><b>为什么不做自动重放</b>：进了死信的是"处理失败<b>且结果未能落库</b>"的消息，
 * 它对应的业务状态是<b>未知</b>的（工单可能停在 `EXECUTING`、导出可能停在 `RUNNING`）。
 * 自动重放等于"把一个自己都说不清状态的操作再执行一遍"——而这里面<b>有写语句</b>。
 * 正确做法是先把"有多少、是哪些"变得可见，由人判断之后再决定重放还是标失败。
 *
 * <h2>⚠️ 已知的有损之处</h2>
 *
 * <p>"读不到"和"是空的"被合并成了 0。严格说应该分开（读不到 = 未知），
 * 但那需要一个额外的"最后一次成功读取时间"指标。
 * 当前取舍是：<b>宁可把 Broker 挂掉误报成"队列是空的"，也不要让整个指标端点挂掉</b>——
 * 而且 Broker 挂掉本身有 `/actuator/health` 会报出来。
 */
@Slf4j
@Component
public class DlqDepthMonitor {

    /** 要盯的死信队列。 */
    private static final List<String> WATCHED_QUEUES = List.of(
            RabbitConfig.EXPORT_DLQ,
            RabbitConfig.REVIEW_DLQ);

    private final RabbitTemplate rabbitTemplate;

    public DlqDepthMonitor(RabbitTemplate rabbitTemplate, SqlPlatformMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;

        for (String queue : WATCHED_QUEUES) {
            metrics.registerQueueDepthGauge(queue, () -> depthOf(queue));
        }
    }

    /**
     * 读队列深度。<b>任何异常都吞掉并返回 0</b> —— 见类注释最后一节。
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
     * <p>队列不存在时会抛 {@code IOException}（404 NOT_FOUND），照样被吞掉返回 0。
     */
    private double depthOf(String queue) {
        try {
            Long count = rabbitTemplate.execute(channel -> {
                // passive=true：只查询、不创建。队列不存在时抛 404
                return (long) channel.queueDeclarePassive(queue).getMessageCount();
            });
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 定时把"非空"这件事打进日志。
     *
     * <p>指标要接了告警才有用；日志是给"还没接告警"的环境兜底的 ——
     * 而绝大多数环境都还没接。
     */
    @Scheduled(cron = "${mq.dlq-check-cron:0 */10 * * * ?}")
    public void reportNonEmptyDlq() {
        for (String queue : WATCHED_QUEUES) {
            long depth = (long) depthOf(queue);
            if (depth > 0) {
                log.error("死信队列 {} 里有 {} 条消息 —— 说明有任务被处理失败、且结果未能落库，"
                                + "需要人工查看（本系统不会自动重放：那些消息对应的业务状态是未知的）。",
                        queue, depth);
            }
        }
    }
}
