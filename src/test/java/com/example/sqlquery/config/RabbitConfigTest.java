package com.example.sqlquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息体的序列化格式。
 *
 * <h2>为什么值得单独钉一条测试</h2>
 *
 * <p>换消息序列化格式是一个<b>不会在编译期、也不会在启动期暴露</b>的改动：
 * 生产者发出的消息，消费者能不能解开，只有真的跑一遍才知道。
 * 而失败的表现是<b>消息静默进死信队列</b>（消费者反序列化失败 → nack），
 * 从接口上看一切正常。
 *
 * <p>这条测试验的是"发了什么、能不能读回来"，不需要真的起 RabbitMQ ——
 * 因为要验的恰恰是"字节长什么样"， Broker 在这件事上不参与。
 */
class RabbitConfigTest {

    private final MessageConverter converter = new RabbitConfig().jsonMessageConverter();

    @Test
    @DisplayName("⭐ 消息体是可读的 JSON，不是 JDK 序列化的二进制")
    void payloadShouldBeReadableJson() {
        Message message = converter.toMessage(42L, new MessageProperties());
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        // 这一条是"能在 RabbitMQ 管理台上直接看懂队列里是什么"的前提
        assertEquals("42", body, "Long 应该被序列化成 JSON 数字，实际：" + body);

        // 默认的 SimpleMessageConverter 对 Long 走的是 Java 原生序列化，
        // 字节开头是魔数 0xAC 0xED，管理台上显示为一串乱码
        Message legacy = new SimpleMessageConverter().toMessage(42L, new MessageProperties());
        assertNotEquals(new String(legacy.getBody(), StandardCharsets.UTF_8), body,
                "如果两者一样，说明这个 Bean 根本没起作用（还在用默认转换器）");
        assertEquals((byte) 0xAC, legacy.getBody()[0],
                "默认转换器的产物以 Java 序列化魔数 0xAC 开头 —— 正是要摆脱的东西");
    }

    @Test
    @DisplayName("能原样读回来（生产端和消费端用的是同一个转换器）")
    void payloadShouldRoundTrip() {
        Message message = converter.toMessage(42L, new MessageProperties());
        assertEquals(42L, converter.fromMessage(message));
    }

    @Test
    @DisplayName("带类型信息，消费者不需要猜")
    void messageShouldCarryTypeInformation() {
        Message message = converter.toMessage(42L, new MessageProperties());

        // Jackson2JsonMessageConverter 会把类型写进 __TypeId__ 头，
        // 消费端据此把 JSON 还原成正确的类型 ——
        // 少了它，消费端只能拿到一个 String，而 @RabbitListener 的参数是 Long。
        String typeId = message.getMessageProperties().getHeader("__TypeId__");
        assertEquals("java.lang.Long", typeId);
    }

    @Test
    @DisplayName("消息是持久化的 —— 和队列的 durable 配套，否则 Broker 重启就丢")
    void messageShouldBePersistent() {
        Message message = converter.toMessage(42L, new MessageProperties());

        // Jackson2JsonMessageConverter 默认把 deliveryMode 设为 PERSISTENT。
        // 缺了它，队列即使声明成 durable，消息也只在内存里 ——
        // Broker 重启后队列还在，但里面的消息没了。
        assertEquals(MessageDeliveryMode.PERSISTENT,
                message.getMessageProperties().getDeliveryMode());
    }

    @Test
    @DisplayName("两个死信队列的拓扑与绑定都要在（防止有人删掉其中一半）")
    void deadLetterTopologyShouldBeDeclared() {
        RabbitConfig config = new RabbitConfig();

        assertEquals(RabbitConfig.EXPORT_QUEUE, config.exportQueue().getName());
        assertEquals(RabbitConfig.EXPORT_DLQ, config.exportDeadLetterQueue().getName());
        assertEquals(RabbitConfig.REVIEW_QUEUE, config.reviewQueue().getName());
        assertEquals(RabbitConfig.REVIEW_DLQ, config.reviewDeadLetterQueue().getName());

        // 队列必须有容量上限，而且满了以后必须是 reject-publish-dlx 而不是默认的 drop-head ——
        // drop-head 会**静默丢掉队头那条最老的消息**（= 一个等了最久的导出任务凭空消失）
        for (Queue q : java.util.List.of(config.exportQueue(), config.reviewQueue())) {
            assertEquals(10000, q.getArguments().get("x-max-length"), q.getName() + " 必须有积压上限");
            assertEquals("reject-publish-dlx", q.getArguments().get("x-overflow"),
                    q.getName() + " 满了以后必须转投死信，不能丢队头");
        }

        // 队列必须带死信参数，否则"处理不掉的消息进死信"这条链是断的
        assertTrue(config.exportQueue().getArguments().containsKey("x-dead-letter-exchange"));
        assertTrue(config.reviewQueue().getArguments().containsKey("x-dead-letter-exchange"));

        // ⚠️ 两条链路的死信交换机必须是**分开**的：
        //    落进死信的消息是给人看的，运维要能一眼分辨
        //    "是导出处理不掉，还是工单处理不掉" —— 两者处置方式完全不同。
        assertNotEquals(config.exportDeadLetterExchange().getName(),
                config.reviewDeadLetterExchange().getName());
        assertFalse(config.exportQueue().getArguments().get("x-dead-letter-exchange")
                        .equals(config.reviewQueue().getArguments().get("x-dead-letter-exchange")),
                "两条链路不能共用一个死信交换机");
    }
}
