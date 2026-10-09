package com.example.sqlquery.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 导出队列的拓扑。
 *
 * <h2>为什么必须有死信队列</h2>
 *
 * <p>只声明一个队列时，消息的可靠性只做到了"Broker 持久化"这一层 ——
 * 消息<b>投递到队列</b>是安全的，但"被消费成功"完全没有保障。
 * 一旦消费者把消息 nack 掉（或者重试次数耗尽），RabbitMQ 会直接<b>丢弃</b>它，
 * 没有任何痕迹：任务永远停在 RUNNING，也没有人知道发生过什么。
 *
 * <p>加上死信交换机后，处理不掉的消息会进 {@link #EXPORT_DLQ}，
 * 可以人工查看、重放，或者接告警。这是"消息不丢"这句话里最后那一段。
 */
@Configuration
public class RabbitConfig {

    public static final String EXPORT_QUEUE = "export.queue";

    /** 死信交换机：专门接收被拒绝 / 过期 / 超限的消息。 */
    public static final String EXPORT_DLX = "export.dlx";

    /** 死信队列：导出任务最终处理不掉时，消息落到这里。 */
    public static final String EXPORT_DLQ = "export.dlq";

    /**
     * 业务队列。
     *
     * <p>{@code deadLetterExchange} 指定死信去向；路由键沿用队列名，
     * 由下面的 binding 把 {@code EXPORT_DLX} 上这个路由键绑到死信队列。
     *
     * <p>⚠️ <b>升级注意</b>：给一个<b>已存在</b>的队列加参数，
     * Broker 会因为"同名队列参数不一致"报 {@code PRECONDITION_FAILED (406)}。
     * 从旧版本升级上来的环境需要先删掉旧的 {@code export.queue}
     * （管理台 Queues → Delete，或 {@code rabbitmqctl delete_queue export.queue}），
     * 应用启动时会按新参数重新声明。这一步写在 README 的升级说明里。
     */
    @Bean
    public Queue exportQueue() {
        return QueueBuilder.durable(EXPORT_QUEUE)
                .deadLetterExchange(EXPORT_DLX)
                .deadLetterRoutingKey(EXPORT_QUEUE)
                .build();
    }

    @Bean
    public DirectExchange exportDeadLetterExchange() {
        return new DirectExchange(EXPORT_DLX, true, false);
    }

    @Bean
    public Queue exportDeadLetterQueue() {
        return QueueBuilder.durable(EXPORT_DLQ).build();
    }

    @Bean
    public Binding exportDeadLetterBinding(Queue exportDeadLetterQueue,
                                           DirectExchange exportDeadLetterExchange) {
        return BindingBuilder.bind(exportDeadLetterQueue)
                .to(exportDeadLetterExchange)
                .with(EXPORT_QUEUE);
    }
}
