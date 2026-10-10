package com.example.sqlquery.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列的拓扑：导出队列 + 工单执行队列，各自带一条死信链路。
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

    // ------------------------------------------------------------------
    // 队列容量上限（两条链路通用）
    // ------------------------------------------------------------------

    /**
     * 单个业务队列最多积压多少条消息。
     *
     * <h2>为什么必须设上限</h2>
     *
     * <p>不限长的队列在「消费者停了但生产者还在发」时会把 Broker 的内存吃掉 ——
     * RabbitMQ 在内存水位告警后<b>阻塞所有生产者</b>，
     * 于是新任务提交不进去，连带着其它接口也一起发不出消息。
     * 一个队列把 Broker 拖垮，影响的是<b>所有</b>用这个 Broker 的东西。
     *
     * <p>{@code 10000} 这个数按量级估：单条导出任务消息就是几个字节的 {@code Long}，
     * 10000 条的元数据开销在几 MB 量级，远小于默认内存水位；
     * 而正常运行时队列深度是<b>个位数</b>（实测见压测报告），
     * 能涨到 10000 只可能是消费者死透了 —— 那就是该告警的时候。
     *
     * <p>⚠️ 死信队列<b>刻意不设上限</b>：落到那里的消息是给人看的，
     * 而且已经出过一次问题了，再丢一次就真的没有痕迹了。
     * 靠 {@link DlqDepthMonitor} 的深度巡检告警来提醒人去处理。
     */
    @Value("${mq.queue.max-length:10000}")
    private int queueMaxLength = 10000;

    /**
     * 队列满了以后，新来的消息怎么办：{@code reject-publish-dlx}。
     *
     * <h2>为什么不是默认的 {@code drop-head}</h2>
     *
     * <p>RabbitMQ 的默认行为是 {@code drop-head}：<b>丢掉队头那条最老的消息</b>，
     * 给新消息腾位置。放在这个项目里，它的含义是 ——
     * <b>一个已经排队等了很久的导出任务被静默丢弃</b>：
     * 任务停在 PENDING、没有任何报错、用户只会看到「一直在转圈」。
     * 这比「提交时明确报错」糟糕得多。
     *
     * <h2>为什么也不用 {@code reject-publish}</h2>
     *
     * <p>{@code reject-publish} 只是拒绝这次投递，在生产者那一侧表现为一次
     * 否定确认（nack）—— 而 publisher confirm 的回调<b>只打日志</b>
     * （见 {@link RabbitConfirmConfig}），也就是说消息最终还是没了，
     * 只是日志里多了一行。
     *
     * <p>{@code reject-publish-dlx}（RabbitMQ 3.10+，本项目容器是 3.13）会把
     * 被拒的消息<b>转投到死信交换机</b>，于是它落到 {@code export.dlq} /
     * {@code review.dlq} 里，被 {@link DlqDepthMonitor} 的深度巡检看到并告警。
     * <b>消息一条不丢，而且有人知道。</b>
     *
     * <p>⚠️ <b>升级注意</b>：{@code x-max-length} 和 {@code x-overflow} 都是
     * <b>队列参数</b>，给一个已存在的同名队列改参数，Broker 会报
     * {@code PRECONDITION_FAILED (406)} 并拒绝声明。
     * 从旧版本升级上来必须先删掉旧的 {@code export.queue} / {@code review.queue}
     * （{@code rabbitmqctl delete_queue 名字}），应用启动时会按新参数重新声明。
     * 这一步写在 README 的升级说明里。
     */
    @Value("${mq.queue.overflow:reject-publish-dlx}")
    private String queueOverflow = "reject-publish-dlx";
    public static final String EXPORT_QUEUE = "export.queue";

    /** 死信交换机：专门接收被拒绝 / 过期 / 超限的消息。 */
    public static final String EXPORT_DLX = "export.dlx";

    /** 死信队列：导出任务最终处理不掉时，消息落到这里。 */
    public static final String EXPORT_DLQ = "export.dlq";

    /**
     * 消息体用 <b>JSON</b>，不用 JDK 序列化。
     *
     * <h2>为什么必须换掉默认的</h2>
     *
     * <p>不声明这个 Bean 时，Spring AMQP 用的是 {@code SimpleMessageConverter} ——
     * 对非 String / 非 byte[] 的对象它走的是 <b>Java 原生序列化</b>。
     * 那有三个问题，而且一个比一个实际：
     *
     * <ol>
     *   <li><b>看不到消息内容。</b>本项目发的就是几个 {@code Long} 的 id，
     *       但在 RabbitMQ 管理台上显示的是一串二进制乱码 ——
     *       排查"这个任务为什么卡住"时，第一件想做的事就是看一眼队列里到底是什么；</li>
     *   <li><b>JDK 反序列化是一个已知的攻击面。</b>队列里如果能被塞进一条
     *       精心构造的消息，反序列化就可能变成任意代码执行。
     *       本项目的队列不对公网开放，但"我能往队列里发消息"和"我能执行代码"
     *       之间不该只隔一层序列化；</li>
     *   <li><b>和语言/版本绑死。</b>类名和 serialVersionUID 一变，
     *       队列里的历史消息就解不开了。</li>
     * </ol>
     *
     * <p>换成 JSON 之后这三点都没了：管理台上直接看得见
     * {@code 42}、{@code {"taskId":42}} 这样的内容。
     *
     * <p>声明成 Bean 就够了 —— Spring Boot 的 {@code RabbitTemplate} 和监听容器工厂
     * 都会自动取这个 {@link MessageConverter}，生产端和消费端一起换掉，
     * 不需要在两处分别配。
     *
     * <p>⚠️ <b>升级注意</b>：格式变了，队列里<b>已经存在</b>的旧消息（JDK 序列化的）
     * 在新消费者手里会反序列化失败 → nack → 进死信队列。
     * 升级前应当先清空队列，或者接受这些消息进死信。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 导出业务队列。
     *
     * <p>{@code deadLetterExchange} 指定死信去向；死信路由键沿用队列名，
     * 由下面的 binding 把 {@code EXPORT_DLX} 上这个路由键绑到死信队列。
     *
     * <p>另外带两个容量参数（{@code x-max-length} / {@code x-overflow}），
     * 见类开头 {@link #queueMaxLength} 的说明。
     */
    @Bean
    public Queue exportQueue() {
        return QueueBuilder.durable(EXPORT_QUEUE)
                .withArgument("x-max-length", queueMaxLength)
                .withArgument("x-overflow", queueOverflow)
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

    // ------------------------------------------------------------------
    // 工单执行队列
    // ------------------------------------------------------------------

    public static final String REVIEW_QUEUE = "review.queue";

    public static final String REVIEW_DLX = "review.dlx";

    public static final String REVIEW_DLQ = "review.dlq";
    /**
     * 审批通过之后<b>异步</b>执行的队列。
     *
     * <h2>为什么不直接在审批接口里同步执行</h2>
     *
     * <p>审批人点「通过」之后要跑的那条 SQL，是这个平台上<b>最不可控</b>的一条 ——
     * 它是一条 {@code UPDATE}，可能扫几十万行。
     * 同步执行意味着：
     *
     * <ul>
     *   <li>审批人的 HTTP 请求要等它跑完，网关一超时，审批人看到的是「失败」，
     *       但数据库里那条 SQL <b>其实还在跑、而且会跑完</b> ——
     *       他会以为没生效，于是再点一次；</li>
     *   <li>执行占用的是 Web 线程，几个慢工单就能把线程池占满，
     *       连「查一条 SELECT」都进不来。</li>
     * </ul>
     *
     * <p>异步之后审批是一次<b>状态变更</b>（PENDING → APPROVED，毫秒级），
     * 执行是另一件事。审批人的请求立刻返回，执行进度去工单详情里看。
     *
     * <h2>为什么和导出共用一个死信交换机不行</h2>
     *
     * <p>技术上可以（都是 Direct），但死信队列必须<b>分开</b>：
     * 落到死信里的消息是给人看的，运维需要一眼分辨
     * 「是导出任务处理不掉，还是工单执行处理不掉」——
     * 两者的处置方式完全不同（导出重跑就行，工单要人工确认数据有没有被改动）。
     */
    @Bean
    public Queue reviewQueue() {
        return QueueBuilder.durable(REVIEW_QUEUE)
                .withArgument("x-max-length", queueMaxLength)
                .withArgument("x-overflow", queueOverflow)
                .deadLetterExchange(REVIEW_DLX)
                .deadLetterRoutingKey(REVIEW_QUEUE)
                .build();
    }

    @Bean
    public DirectExchange reviewDeadLetterExchange() {
        return new DirectExchange(REVIEW_DLX, true, false);
    }

    @Bean
    public Queue reviewDeadLetterQueue() {
        return QueueBuilder.durable(REVIEW_DLQ).build();
    }

    @Bean
    public Binding reviewDeadLetterBinding(Queue reviewDeadLetterQueue,
                                           DirectExchange reviewDeadLetterExchange) {
        return BindingBuilder.bind(reviewDeadLetterQueue)
                .to(reviewDeadLetterExchange)
                .with(REVIEW_QUEUE);
    }
}
