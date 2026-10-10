package com.example.sqlquery.config;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死信深度指标：<b>"读不到"必须与"是空的"分开</b>。
 *
 * <p>这条性质是被一次真实的坑逼出来的：早期实现把"读队列深度"直接放进 Gauge 的 Supplier，
 * 而 Supplier 跑在 Prometheus 抓取线程上 —— Broker 不可达时抓取要等 AMQP 超时，
 * 结果是<b>故障期间连"Broker 挂了"这个指标本身都抓不到</b>。
 *
 * <p>改成快照之后必须补上另一半：快照可能会过期。所以这里同时钉住三件事：
 * <ol>
 *   <li>抓取路径只读内存，不发 AMQP；</li>
 *   <li>读失败时<b>保留上一次读数</b>，绝不清零（清零 = 把"未知"显示成"没问题"）；</li>
 *   <li>读失败被计数，且新鲜度指标在推进。</li>
 * </ol>
 */
class DlqDepthMonitorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);
    private RabbitTemplate rabbitTemplate;
    private DlqDepthMonitor monitor;

    @BeforeEach
    void setUp() {
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        monitor = new DlqDepthMonitor(rabbitTemplate, metrics);
    }

    /** 让 RabbitTemplate.execute 直接在 mock 出来的 channel 上跑回调。 */
    private void channelReports(long count) {
        Channel channel = Mockito.mock(Channel.class);
        AMQP.Queue.DeclareOk ok = Mockito.mock(AMQP.Queue.DeclareOk.class);
        Mockito.when(ok.getMessageCount()).thenReturn((int) count);
        try {
            Mockito.when(channel.queueDeclarePassive(ArgumentMatchers.anyString())).thenReturn(ok);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // ⚠️ 必须用 doAnswer(...).when(...)，不能写 when(...).thenAnswer(...)：
        //    后者的 when(...) 会**真的调用一次** mock，于是上一次打好的桩会被
        //    用一个 null 回调执行一遍 —— 重新打桩时正好踩到（本用例第一次就是这么红的）
        Mockito.doAnswer(invocation -> {
            org.springframework.amqp.rabbit.core.ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        }).when(rabbitTemplate).execute(ArgumentMatchers.any());
    }

    private void channelFails() {
        Mockito.doThrow(new AmqpException("Broker 不可达"))
                .when(rabbitTemplate).execute(ArgumentMatchers.any());
    }

    private Gauge depthGauge(String queue) {
        Gauge gauge = registry.find(SqlPlatformMetrics.QUEUE_DEPTH).tag("queue", queue).gauge();
        assertNotNull(gauge, "没有注册 " + queue + " 的深度指标");
        return gauge;
    }

    private double readFailures(String queue) {
        var counter = registry.find(SqlPlatformMetrics.DLQ_READ_FAILED).tag("queue", queue).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("正常刷新后，指标反映真实深度")
    void refreshShouldPublishDepth() {
        channelReports(7);

        monitor.refreshDepths();

        assertEquals(7.0, depthGauge(RabbitConfig.EXPORT_DLQ).value());
        assertEquals(7.0, depthGauge(RabbitConfig.REVIEW_DLQ).value());
        assertEquals(0.0, readFailures(RabbitConfig.EXPORT_DLQ));
    }

    @Test
    @DisplayName("⭐ 读不到时保留上次读数：清零等于把\"未知\"显示成\"队列是空的\"")
    void readFailureShouldKeepLastKnownDepth() {
        channelReports(5);
        monitor.refreshDepths();
        assertEquals(5.0, depthGauge(RabbitConfig.EXPORT_DLQ).value());

        channelFails();
        monitor.refreshDepths();

        assertEquals(5.0, depthGauge(RabbitConfig.EXPORT_DLQ).value(),
                "读失败时不能把读数改成 0 —— 那会让\"Broker 挂了\"看起来像\"队列正常\"");
        assertEquals(1.0, readFailures(RabbitConfig.EXPORT_DLQ),
                "读失败必须计数，否则\"读不到\"在这条线上完全不可见");
        assertEquals(1.0, readFailures(RabbitConfig.REVIEW_DLQ));
    }

    @Test
    @DisplayName("新鲜度指标必须存在：它是区分\"真空了\"和\"读不到\"的唯一依据")
    void ageGaugeShouldExist() {
        channelReports(3);
        monitor.refreshDepths();

        Gauge age = registry.find(SqlPlatformMetrics.QUEUE_DEPTH_AGE)
                .tag("queue", RabbitConfig.EXPORT_DLQ).gauge();
        assertNotNull(age, "没有注册新鲜度指标 —— 快照过期就没人看得见了");
        assertTrue(age.value() >= 0);
    }

    @Test
    @DisplayName("抓取路径不发 AMQP：读指标本身绝不能被 Broker 拖住")
    void gaugeReadShouldNotTouchBroker() {
        channelReports(9);
        monitor.refreshDepths();
        Mockito.clearInvocations(rabbitTemplate);

        // 模拟若干次 Prometheus 抓取
        for (int i = 0; i < 5; i++) {
            assertEquals(9.0, depthGauge(RabbitConfig.EXPORT_DLQ).value());
            assertTrue(registry.find(SqlPlatformMetrics.QUEUE_DEPTH_AGE)
                    .tag("queue", RabbitConfig.EXPORT_DLQ).gauge().value() >= 0);
        }

        Mockito.verify(rabbitTemplate, Mockito.never()).execute(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("巡检日志先刷新快照，所以它说的数字是此刻的")
    void reportShouldRefreshBeforeLogging() {
        channelReports(0);
        monitor.reportNonEmptyDlq();  // 不应抛异常
        Mockito.verify(rabbitTemplate, Mockito.atLeast(2)).execute(ArgumentMatchers.any());

        // Broker 挂掉时它同样不能抛（这条路径在定时线程上，抛出去任务就废了）
        channelFails();
        monitor.reportNonEmptyDlq();
    }
}
