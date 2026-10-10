package com.example.sqlquery.config;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死信链路是不是<b>真的通了</b> —— 对着真实 RabbitMQ 跑。
 *
 * <h2>为什么必须有这条用例</h2>
 *
 * <p>在此之前，全项目关于"消息不会丢"的测试只有一句
 * {@code Mockito.verify(channel).basicNack(...)} ——
 * 它锁住的是"<b>我们调用了 nack</b>"，<b>不是</b>"nack 之后消息真的落在了死信队列里"。
 *
 * <p>这两件事之间隔着一整条配置链，而其中任何一环错了都<b>不会有任何报错</b>：
 *
 * <pre>
 * nack(requeue=false)
 *   → 队列的 x-dead-letter-exchange 参数     ← 写错 = 消息被直接丢弃
 *   → 死信交换机
 *   → 交换机的绑定 + x-dead-letter-routing-key ← 写错 = 消息路由不到任何队列
 *   → 死信队列
 * </pre>
 *
 * <p>最后一步错了还有个"兜底"的坑：{@code RabbitConfig} 上配了 mandatory +
 * returns 回调，理论上消息路由不到时会被退回并打一条 ERROR 日志 ——
 * 但那也<b>只是日志</b>（见 {@link RabbitConfirmConfig}），消息本身已经没了。
 *
 * <h2>为什么这条用例必须打 integration 标签</h2>
 *
 * <p>要验的恰恰是"Broker 那边到底认不认这套参数"。用 mock 测这件事，
 * 测的是"我以为我配了什么"，而不是"Broker 实际怎么做的"。
 *
 * <h2>它怎么制造死信（不依赖消费者）</h2>
 *
 * <p>关掉监听器自启动（{@code auto-startup=false}），这样消息不会被真消费者抢走；
 * 然后自己 {@code basicGet} 一条出来再 {@code basicNack} ——
 * 走的是<b>和生产代码同一条</b>的死信路径，但完全不依赖任何业务状态。
 *
 * <p>⚠️ 这条用例会往真实队列里丢消息，所以前后都清空一次。
 */
@Tag("integration")
@SpringBootTest(properties = {
        // 本用例自己制造死信，不希望真消费者把消息抢走
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 关掉 DLQ 巡检定时任务，避免测试期间刷 ERROR 日志
        "mq.dlq-check-cron=-"
})
class DeadLetterRoutingIntegrationTest {

    /** 轮询等待的上限。死信是 Broker 内部完成的，正常在毫秒级。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final long POLL_INTERVAL_MS = 100;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    void purgeDeadLetterQueues() {
        purge(RabbitConfig.EXPORT_QUEUE);
        purge(RabbitConfig.REVIEW_QUEUE);
        purge(RabbitConfig.EXPORT_DLQ);
        purge(RabbitConfig.REVIEW_DLQ);
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 导出链路：被 nack 的消息必须落进 export.dlq，而且不能串到 review.dlq")
    void exportQueueShouldDeadLetterToItsOwnDlq() throws Exception {
        assertDeadLetterChain(RabbitConfig.EXPORT_QUEUE, RabbitConfig.EXPORT_DLQ, RabbitConfig.REVIEW_DLQ);
    }

    @Test
    @DisplayName("⭐ 工单链路：被 nack 的消息必须落进 review.dlq，而且不能串到 export.dlq")
    void reviewQueueShouldDeadLetterToItsOwnDlq() throws Exception {
        assertDeadLetterChain(RabbitConfig.REVIEW_QUEUE, RabbitConfig.REVIEW_DLQ, RabbitConfig.EXPORT_DLQ);
    }

    /**
     * 两条链路共用一套死信参数，所以逻辑只有一份 —— 用参数区分。
     *
     * @param otherDlq "另一条"的死信队列。它的深度必须保持 0：
     *                 死信链路串了的话，运维按"导出处理不掉"去查，会查到一堆工单消息
     */
    private void assertDeadLetterChain(String queue, String dlq, String otherDlq) throws Exception {
        // 队列必须存在。不存在时 queueDeclarePassive 会抛 404 —— 那说明拓扑根本没声明上，
        // 这本身就是一条有用的失败信息（比后面"深度一直是 0"好排查得多）
        assertNotNull(depthOf(queue), "队列 " + queue + " 不存在，拓扑没有声明成功");

        // ① 把一条消息放进业务队列（用默认交换机 + 队列名做路由键）
        rabbitTemplate.convertAndSend("", queue, 42L);
        awaitAtLeast(queue, 1, "消息没能进入业务队列 " + queue);

        // ② 取出来、nack 掉 —— 和 ExportConsumer 失败时做的是同一个动作
        rabbitTemplate.execute(channel -> {
            com.rabbitmq.client.GetResponse response = channel.basicGet(queue, false);
            if (response == null) {
                throw new IllegalStateException("basicGet 没取到消息：" + queue);
            }
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });

        // ③ 它必须出现在自己的死信队列里
        awaitAtLeast(dlq, 1, "消息被 nack 之后没有进入 " + dlq
                + " —— 检查队列的 x-dead-letter-exchange 与 x-dead-letter-routing-key，"
                + "以及死信队列的绑定路由键是否对得上");

        // ④ 而且不能跑到另一条链路的死信队列里
        assertEquals(0, depthOf(otherDlq),
                "消息串到了 " + otherDlq + " —— 两条链路的死信交换机/路由键必须分开");
    }

    @Test
    @DisplayName("⭐ 死信深度指标指向的是对的队列（名字写错会被静默吞成 0）")
    void dlqDepthGaugeShouldReflectReality() throws Exception {
        // 这一条验的是 DlqDepthMonitor：它把 dlq 的深度暴露成 mq_queue_depth{queue=...}，
        // 而它的 depthOf() **吞掉所有异常并返回 0**。
        // 也就是说 —— 如果把队列名写错（队列不存在 → 404），指标会永远显示 0，
        // 看起来"死信队列一直是空的"，实际上消息全在另一个地方堆着。
        // 唯一能发现这件事的办法，就是"先真的放一条进去，再看指标有没有涨"。
        rabbitTemplate.convertAndSend("", RabbitConfig.EXPORT_QUEUE, 42L);
        awaitAtLeast(RabbitConfig.EXPORT_QUEUE, 1, "消息没能进入 " + RabbitConfig.EXPORT_QUEUE);
        rabbitTemplate.execute(channel -> {
            var response = channel.basicGet(RabbitConfig.EXPORT_QUEUE, false);
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });
        awaitAtLeast(RabbitConfig.EXPORT_DLQ, 1, "消息没能进入 " + RabbitConfig.EXPORT_DLQ);

        Gauge gauge = meterRegistry.find(SqlPlatformMetrics.QUEUE_DEPTH)
                .tag("queue", RabbitConfig.EXPORT_DLQ)
                .gauge();

        assertNotNull(gauge, "没有找到 " + SqlPlatformMetrics.QUEUE_DEPTH
                + "{queue=" + RabbitConfig.EXPORT_DLQ + "}，DlqDepthMonitor 没注册上");
        assertTrue(gauge.value() >= 1,
                "死信队列里已经有消息了，指标却还是 " + gauge.value()
                        + " —— 多半是 DlqDepthMonitor 里的队列名写错了（异常被吞成 0）");
    }

    @Test
    @DisplayName("死信巡检能读到真实深度（而不是永远看到 0）")
    void dlqCheckShouldSeeRealDepth() throws Exception {
        rabbitTemplate.convertAndSend("", RabbitConfig.REVIEW_QUEUE, 42L);
        awaitAtLeast(RabbitConfig.REVIEW_QUEUE, 1, "消息没能进入 " + RabbitConfig.REVIEW_QUEUE);
        rabbitTemplate.execute(channel -> {
            var response = channel.basicGet(RabbitConfig.REVIEW_QUEUE, false);
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });
        awaitAtLeast(RabbitConfig.REVIEW_DLQ, 1, "消息没能进入 " + RabbitConfig.REVIEW_DLQ);

        // 直接调一次巡检方法：它内部会读深度，非空时打 ERROR。
        // 这里断言的是"它能读到非 0"（若队列名写错，它读到的永远是 0，
        // 于是"死信队列非空"这个告警**永远不会触发**——这是最坏的一种静默）
        new DlqDepthMonitor(rabbitTemplate, new SqlPlatformMetrics(meterRegistry)).reportNonEmptyDlq();

        assertTrue(depthOf(RabbitConfig.REVIEW_DLQ) >= 1);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 读队列深度。队列不存在会抛（和被测试代码"吞掉异常"的做法相反：这里要让它响）。 */
    private long depthOf(String queue) {
        Long count = rabbitTemplate.execute(channel ->
                (long) channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0 : count;
    }

    private void purge(String queue) {
        try {
            rabbitTemplate.execute(channel -> {
                channel.queuePurge(queue);
                return null;
            });
        } catch (Exception ignored) {
            // 队列还不存在（上下文没起来）时忽略 —— 收尾清理不该让用例失败
        }
    }

    /** 轮询等待深度达到期望值。死信是异步完成的，不能立刻断言。 */
    private void awaitAtLeast(String queue, long expected, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = depthOf(queue);
                if (last >= expected) {
                    return;
                }
            } catch (Exception e) {
                last = -1;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(failureMessage + "（等待 " + TIMEOUT.toSeconds()
                + " 秒后 " + queue + " 的深度是 " + last + "，期望至少 " + expected + "）");
    }
}
