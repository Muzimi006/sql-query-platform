package com.example.sqlquery.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * 发布方确认（publisher confirm）与不可路由消息的回收。
 *
 * <h2>不开这个之前，"消息发出去了"是一句没有根据的话</h2>
 *
 * <p>{@code convertAndSend} 是<b>异步</b>的：它成功返回只代表"消息交给了客户端库"，
 * 不代表 Broker 收到了。交换机遇不到、队列不存在、Broker 在收到之前重启，
 * 这些情况下调用方拿到的都是一个平静的返回值，而消息已经没了。
 * 结果就是导出任务永远停在 PENDING，前端轮询到天荒地老。
 *
 * <p>三件事必须一起配，缺任何一个都等于没配：
 * <ol>
 *   <li>{@code spring.rabbitmq.publisher-confirm-type=correlated}
 *       —— 让 Broker 对每条消息回一个确认；</li>
 *   <li>{@code spring.rabbitmq.template.mandatory=true}
 *       —— 消息路由不到任何队列时，让 Broker 把它退回（return）而不是静默丢弃；</li>
 *   <li><b>本类</b> —— 上面两个开关只是让 Broker"愿意告诉你"，
 *       真正接收确认、并把结果<b>记进日志</b>的是这两个回调。
 *       只配开关不写回调，是"看起来配了、实际什么也没发生"。</li>
 * </ol>
 *
 * <p>当前对<b>否定确认</b>的处理是"打 ERROR 日志 + 留给人处理"，
 * <b>回调本身一个字都不写库</b>（它只打日志）。
 * 真正兜底的是两个独立的定时任务：{@link ExportTaskRecoveryJob} 与
 * {@link SqlReviewRecoveryJob}。
 *
 * <p>⚠️ 这句话以前写成"回调触发补偿"，是错的 —— 会让人以为补偿依赖回调，
 * 从而在排查时去找"回调有没有跑"。补偿完全是定时任务自己扫出来的，与回调无关。
 *
 * <p>没有自动改任务状态的原因：确认回调运行在独立的线程里，
 * 在这里改数据库要有重试与幂等，而任务本身已经有补偿任务兜底
 * （{@link ExportTaskRecoveryJob} 会把超时未完成的判为失败）。
 * 这一步的边界是有意画在这里的，不是漏了。
 */
@Slf4j
@Configuration
public class RabbitConfirmConfig {

    private final SqlPlatformMetrics metrics;

    public RabbitConfirmConfig(RabbitTemplate rabbitTemplate, SqlPlatformMetrics metrics) {
        this.metrics = metrics;
        rabbitTemplate.setConfirmCallback(this::onConfirm);
        rabbitTemplate.setReturnsCallback(this::onReturned);
    }

    /** Broker 的确认回调：{@code ack=true} 表示消息已落盘，false 表示 Broker 拒收。 */
    private void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String correlationId = correlationData == null ? "(无)" : correlationData.getId();

        if (ack) {
            log.debug("消息已被 Broker 确认。correlationId={}", correlationId);
            return;
        }

        // ⭐ 这里也要计数：在这之前 `mq_publish_failed` **只覆盖"convertAndSend 同步抛异常"**
        //    那一半路径，而"调用成功了、但 Broker 随后否认"这一半只有日志。
        //    两者合起来才是完整的"消息没进 Broker"。
        metrics.recordPublishFailure(linkOf(correlationId));

        // 走到这里说明消息<b>没有</b>进入 Broker，对应任务会一直停在初始状态。
        //
        // ⚠️ 两条链路的措辞必须分开。工单停在的是 APPROVED 而不是 PENDING ——
        // 运维按"PENDING"去找工单会一无所获，而排障时"找不到对应记录"
        // 比"看到一条错误的记录"更费时间。
        //
        // 兜底：ExportTaskRecoveryJob 处理导出的 PENDING/RUNNING，
        // SqlReviewRecoveryJob 处理工单的 APPROVED（重投）和 EXECUTING（判失败）。
        // 命名前缀是唯一能在这里区分两条链路的信息，所以两个 Producer 都带上了。
        if (correlationId != null && correlationId.startsWith("review-")) {
            log.error("消息被 Broker 否定确认，工单将停留在 APPROVED，"
                            + "等待 SqlReviewRecoveryJob 补偿重投。correlationId={}, cause={}",
                    correlationId, cause);
        } else {
            log.error("消息被 Broker 否定确认，导出任务将停留在 PENDING，"
                            + "等待 ExportTaskRecoveryJob 处理。correlationId={}, cause={}",
                    correlationId, cause);
        }
    }

    /**
     * 消息到了交换机但路由不到任何队列时被退回。
     *
     * <p>⚠️ 退回分不出是哪条链路（{@code ReturnedMessage} 里没有 CorrelationData），
     * 所以记成一条不带标签的 {@code mq.message.returned} —— 能告警就够了，定位靠日志里的 exchange/routingKey。
     */
    private void onReturned(org.springframework.amqp.core.ReturnedMessage returned) {
        metrics.recordReturnedMessage();
        log.error("消息无法路由到任何队列，已被退回。exchange={}, routingKey={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
    }

    /** 从 CorrelationData 的 id 认出是哪条链路（两个 Producer 都带前缀）。 */
    private static String linkOf(String correlationId) {
        if (correlationId != null && correlationId.startsWith("review-")) {
            return SqlPlatformMetrics.LINK_REVIEW;
        }
        // 导出侧：新版本带 export- 前缀，旧格式是纯数字 —— 同样按导出处理
        return SqlPlatformMetrics.LINK_EXPORT;
    }
}
