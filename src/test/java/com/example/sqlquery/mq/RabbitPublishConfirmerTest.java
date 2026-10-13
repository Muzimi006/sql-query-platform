package com.example.sqlquery.mq;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布方确认的等待逻辑。
 *
 * <p>它把「消息发出去了」从一句没有根据的话，变成了一句「**有结论**」的话 ——
 * 而这条路径上最容易写错的一点是：
 * <b>否定确认也会完成那个 future</b>，只捕获超时是不够的，必须看 {@code isAck()}。
 *
 * <p>另一个刻意的取舍：<b>超时算失败</b>。理由写在类的 javadoc 里 ——
 * 「其实成功了但被判失败」的代价是多投一次（幂等），
 * 「其实失败了却当成成功」的代价是没人知道（工单拖到放弃时限、导出留孤儿行）。
 */
class RabbitPublishConfirmerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);
    private final RabbitPublishConfirmer confirmer = new RabbitPublishConfirmer(metrics);

    private double unconfirmed() {
        var counter = registry.find(SqlPlatformMetrics.PUBLISH_UNCONFIRMED)
                .tag("link", SqlPlatformMetrics.LINK_EXPORT).counter();
        return counter == null ? 0 : counter.count();
    }

    /** 立刻把确认结果送回来（模拟 Broker 回执）。 */
    private CorrelationData confirmed(boolean ack, String reason) {
        CorrelationData data = new CorrelationData("export-1");
        data.getFuture().complete(new CorrelationData.Confirm(ack, reason));
        return data;
    }

    @Test
    @DisplayName("Broker 确认 → true")
    void ackShouldReturnTrue() {
        assertTrue(confirmer.awaitConfirmed(confirmed(true, null), 100,
                SqlPlatformMetrics.LINK_EXPORT));
        assertEquals(0.0, unconfirmed(), "确认成功不该计数");
    }

    @Test
    @DisplayName("⭐ 否定确认也必须被认出来 —— 队列满时 Broker 走的就是这条路（不抛异常）")
    void nackShouldReturnFalse() {
        assertFalse(confirmer.awaitConfirmed(confirmed(false, "queue full"), 100,
                SqlPlatformMetrics.LINK_EXPORT),
                "否定确认同样会完成 future，只看超时是抓不到它的");
        assertEquals(1.0, unconfirmed());
    }

    @Test
    @DisplayName("等不到确认 → 按失败处理（宁可说「不确定」，也不假装成功）")
    void timeoutShouldReturnFalse() {
        // 一个新的 CorrelationData：它的 future 永远不会完成
        assertFalse(confirmer.awaitConfirmed(new CorrelationData("export-2"), 50,
                SqlPlatformMetrics.LINK_EXPORT));
        assertEquals(1.0, unconfirmed());
    }

    @Test
    @DisplayName("没带 CorrelationData → 无法判断，按失败处理")
    void missingCorrelationDataShouldReturnFalse() {
        assertFalse(confirmer.awaitConfirmed(null, 50, SqlPlatformMetrics.LINK_EXPORT));
        assertEquals(1.0, unconfirmed());
    }

    @Test
    @DisplayName("等待时被中断 → 返回 false 且还原中断标志位（优雅停机不能被吞掉）")
    void interruptShouldReturnFalseAndRestoreFlag() throws Exception {
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean flagRestored = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            result.set(confirmer.awaitConfirmed(new CorrelationData("export-3"), 5000,
                    SqlPlatformMetrics.LINK_EXPORT));
            // 在同一个线程里读标志位：awaitConfirmed 捕获 InterruptedException 之后
            // 必须调用 Thread.currentThread().interrupt() 把它还原（"吞掉中断"是常见错误）
            flagRestored.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();

        assertTrue(done.await(2, TimeUnit.SECONDS), "被中断后应当立刻返回，而不是继续等满 5 秒");
        assertFalse(result.get(), "中断时不能算投递成功");
        assertTrue(flagRestored.get(), "中断标志位必须还原，否则上层以为没人要求停下");
        assertEquals(1.0, unconfirmed());
    }
}
