package com.example.sqlquery.util;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 等一条消息的发布方确认（publisher confirm），把它变成一句<b>有根据的话</b>。

 * <h2>它补的是什么洞</h2>

 * <p>{@code rabbitTemplate.convertAndSend(...)} 是<b>异步</b>的：它正常返回只代表
 * 「消息交给了客户端库」。而项目里配了 {@code x-overflow=reject-publish-dlx} ——
 * 队列满时 Broker 的做法是<b>异步否定确认</b>，<b>不是抛异常</b>。

 * <p>于是出现一种很难查的错位：<b>调用方以为投递成功了，其实消息被拒收、已经进了死信。</b>
 * 具体到本项目有两条路径会因此做出错误的决定：

 * <ul>
 *   <li>工单补偿重投（{@code SqlReviewRecoveryJob.resendOne}）：它靠「投递成功」
 *       来决定要不要把 {@code updated_at} 推到当前 —— 投递其实失败了却也推，
 *       于是<b>下一轮扫描会认为它刚被处理过</b>，重投被推迟甚至拖到放弃重投的时限；</li>
 *   <li>导出投递（{@code ExportServiceImpl}）：它靠「投递成功」决定要不要把任务标 FAILED，
 *       消息被拒收时任务会留下一条<b>没人管的 PENDING 孤儿行</b>，用户当场看到的是「导出成功」。</li>
 * </ul>

 * <h2>它怎么等</h2>

 * <p>不给 {@code CorrelationData} 的话永远等不到确认（Broker 的确认靠它回调回来）。
 * 给上之后 {@code getFuture()} 会在确认到达时完成 —— 注意<b>否定确认也会完成这个 future</b>，
 * 所以拿到结果之后必须看 {@code isAck()}，不能只捕获超时。

 * <h2>⚠️ 超时算成功还是失败</h2>

 * <p>这里选择<b>算失败</b>（返回 false），理由和限流器「Redis 挂了就放行」是同一个思路：
 * 要看<b>哪个方向的损失更小</b>。
 * <ul>
 *   <li>当成失败：最坏情况是「其实成功了但被判失败」→ 工单侧补偿任务下一轮还会重投
 *       （幂等认领会挡住重复执行，代价是多一次投递）；导出侧任务被标 FAILED，
 *       用户重新点一次（导出是只读 SQL，重新发起没有副作用）。</li>
 *   <li>当成成功：最坏情况是「其实失败了却没人知道」→ 工单拖到放弃重投的时限、
 *       导出留下永远 PENDING 的孤儿行，<b>而这两种都要靠人翻日志才能发现</b>。</li>
 * </ul>
 *
 * <p>所以：<b>在不确定的时候，宁可说「我不确定」，也不要假装成功。</b>
 */
@Slf4j
@Component
public class RabbitPublishConfirmer {

    private final SqlPlatformMetrics metrics;

    public RabbitPublishConfirmer(SqlPlatformMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 等这条消息的确认。
     *
     * @param correlationData 投递时带上的那个对象；为 {@code null} 时直接返回 false
     *                        （没有它就拿不到确认，等于「无法判断」，按失败处理）
     * @param timeoutMs       最多等多久。正常在毫秒级返回；给足余量是为了容忍 Broker 抖动
     * @param link            哪条链路，用作指标标签（固定值）
     * @return {@code true} 表示 Broker 已确认收到；{@code false} 表示被拒、超时或无法判断
     */
    // ⚠️ 这里用到了 CorrelationData.Confirm 的 isAck() / getReason()，而它们在 spring-rabbit 4.x
    //    里被标记为 @Deprecated(forRemoval=true, since=4.0)：**4.2 起 Confirm 会变成 record，
    //    访问器改名成 ack() / reason()**。当前依赖的 4.1.0 还没有新方法（javap 确认过），
    //    所以只能用旧的并压掉这个编译警告。升级 spring-boot/spring-amqp 时，
    //    这里是**必须改**的两处（改完删掉这个注解）。
    @SuppressWarnings("removal")
    public boolean awaitConfirmed(CorrelationData correlationData, long timeoutMs, String link) {
        if (correlationData == null) {
            log.warn("投递时没有带 CorrelationData，无法确认这条消息是否送达。link={}", link);
            metrics.recordPublishUnconfirmed(link);
            return false;
        }

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck()) {
                return true;
            }
            // 否定确认：Broker 明确说没收（典型原因是队列满 + reject-publish-dlx）
            log.warn("消息被 Broker 否定确认。link={}, correlationId={}, cause={}",
                    link, correlationData.getId(), confirm == null ? "(空)" : confirm.getReason());
            metrics.recordPublishUnconfirmed(link);
            return false;
        } catch (TimeoutException e) {
            // 超时不等于被拒，只等于「说不清」。按失败处理，理由见类注释
            log.warn("等待消息确认超时（{}ms），按「未送达」处理。link={}, correlationId={}",
                    timeoutMs, link, correlationData.getId());
            metrics.recordPublishUnconfirmed(link);
            return false;
        } catch (ExecutionException e) {
            log.warn("等待消息确认时确认回调抛了异常，按「未送达」处理。link={}, correlationId={}",
                    link, correlationData.getId(), e.getCause());
            metrics.recordPublishUnconfirmed(link);
            return false;
        } catch (InterruptedException e) {
            // 中断要还原标志位：优雅停机时线程被中断，吞掉它会让上层以为没人要求停下
            Thread.currentThread().interrupt();
            log.warn("等待消息确认时线程被中断，按「未送达」处理。link={}, correlationId={}",
                    link, correlationData.getId());
            metrics.recordPublishUnconfirmed(link);
            return false;
        }
    }
}
