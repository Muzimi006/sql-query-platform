package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单链路上「没人管」状态的补偿。
 *
 * <h2>它管的是两种卡死，而这两种的处置方式<b>刚好相反</b></h2>
 *
 * <table border="1">
 *   <caption>两种卡死与各自的处置</caption>
 *   <tr><th>卡在</th><th>意味着</th><th>处置</th></tr>
 *   <tr>
 *     <td>{@link ReviewStatus#APPROVED}</td>
 *     <td>批了，但消息没送到（Broker 当时不可用、路由失败）</td>
 *     <td><b>重新投递</b> —— 安全，因为消费者是条件认领的，
 *         重复消息只会被丢弃，不会重复执行</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ReviewStatus#EXECUTING}</td>
 *     <td>消费者拿了消息，但没能把结果写回去（进程被 kill、连接断开）</td>
 *     <td><b>标记失败，绝不重投</b> —— 见下</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 EXECUTING 卡死不能重投 —— 这是本类最重要的一条</h2>
 *
 * <p>导出任务卡死时，重新跑一次是安全的：读操作没有副作用。
 * <b>写语句没有这个性质。</b>
 *
 * <p>一条 {@code UPDATE account SET balance = balance - 100 WHERE id = 1}
 * 如果在「已经发到数据库、结果还没写回」的瞬间进程被杀：
 * 数据<b>可能已经改了，也可能没有</b>（连接断开时事务回滚）。
 * 这时候自动重跑，最好情况是白干一遍，最坏情况是<b>扣了两次钱</b>。
 *
 * <p>所以这里的处置是：标成 FAILED，并在 error_message 里写明
 * 「是否已生效无法确定，请先核对数据」。
 * <b>把不确定性交给人，而不是用一个「重试」把不确定性变成实际的错误数据。</b>
 *
 * <h2>为什么按 updated_at 判定，而不是 created_at</h2>
 *
 * <p>EXECUTING 是一个<b>刚刚</b>才被认领的状态。
 * 如果按创建时间判，一条提交了两天、刚刚轮到执行的工单会被立刻判成卡死 ——
 * 而它其实正常得很。
 *
 * <p>用 updated_at 就对了：认领那次 UPDATE 会把 updated_at 刷成当下，
 * 表上又有 {@code ON UPDATE CURRENT_TIMESTAMP} 兜着（见 migrate_003）。
 *
 * <p>📌 顺带说明为什么不给这条查询加索引：条件永远是
 * {@code status = 'EXECUTING' AND updated_at < ?}，而 EXECUTING 的行数
 * <b>正常情况下是 0</b>，出问题也就几条。走 {@code idx_status_created}
 * 的最左前缀（status）已经足够，为一个只扫几行的查询再加一个索引是净亏。
 */
@Slf4j
@Component
public class SqlReviewRecoveryJob {

    /** 单次补偿最多处理多少条 —— 补偿任务自己也不能把整张表捞进内存。 */
    private static final int BATCH_SIZE = 200;

    /**
     * APPROVED 停留超过这个时长就重新投递。
     *
     * <p>取值依据：它必须明显大于「消息在正常队列里排队 + 被消费」的最坏耗时，
     * 否则会把一条<b>只是排在别人后面</b>的工单当成投递失败。
     * 正常排队是秒级到分钟级，10 分钟足够宽松。
     *
     * <p>重复投递<b>不会</b>导致重复执行：消费者认领时要求状态仍是 APPROVED，
     * 第一条消息认领成功后，后来那些只会被 ack 掉。
     */
    @Value("${review.stuck-approved-minutes:10}")
    private int stuckApprovedMinutes;

    /**
     * 重投最多持续多久，超过就放弃并标记失败。
     *
     * <p>没有这个上限的话，Broker 长期不可用会让补偿任务每 5 分钟重投一次、
     * <b>永远重投下去</b>，日志被刷满而没有任何人注意到。
     *
     * <p>24 小时是一个「运维一定已经看到告警了」的尺度。
     */
    @Value("${review.stuck-approved-max-hours:24}")
    private int stuckApprovedMaxHours;

    /**
     * EXECUTING 停留超过这个时长就认定执行中断。
     *
     * <p>⚠️ 它必须<b>明显大于</b> {@code review.query-timeout-seconds}（默认 120 秒）：
     * 一条执行到一半、正被 JDBC 超时打断的 SQL，在阈值内属于正常。
     * 取 15 分钟 ≈ 2 分钟执行上限 + 13 分钟余量。
     *
     * <p><b>调大 review.query-timeout-seconds 时必须同步调大这个值</b>，
     * 否则补偿任务会把仍在正常执行的工单判成失败 ——
     * 而消费者跑完之后又会把结果写回去，用户看到的状态会前后矛盾。
     */
    @Value("${review.stuck-executing-minutes:15}")
    private int stuckExecutingMinutes;

    private final SqlReviewRequestMapper reviewMapper;
    private final RabbitTemplate rabbitTemplate;

    public SqlReviewRecoveryJob(SqlReviewRequestMapper reviewMapper,
                                RabbitTemplate rabbitTemplate) {
        this.reviewMapper = reviewMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 默认每 5 分钟扫一次。 */
    @Scheduled(cron = "${review.recovery-cron:0 */5 * * * ?}")
    public void recover() {
        resendStuckApproved();
        failStuckExecuting();
    }

    /** APPROVED 卡住 → 重新投递执行消息（重复投递是安全的）。 */
    private void resendStuckApproved() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resendBefore = now.minusMinutes(stuckApprovedMinutes);
        LocalDateTime giveUpBefore = now.minusHours(stuckApprovedMaxHours);

        for (SqlReviewRequest request : selectStuck(ReviewStatus.APPROVED, resendBefore)) {
            try {
                resendOne(request, giveUpBefore);
            } catch (Exception e) {
                // 逐条捕获：一条投递失败不能把这一轮剩下的全跳过。
                // 定时任务里,"一个元素抛异常导致整批不处理"是很常见也很隐蔽的问题。
                log.error("补偿重投失败。reviewId={}", request.getId(), e);
            }
        }
    }

    private void resendOne(SqlReviewRequest request, LocalDateTime giveUpBefore) {
        if (request.getUpdatedAt() != null && request.getUpdatedAt().isBefore(giveUpBefore)) {
            int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                    .eq(SqlReviewRequest::getId, request.getId())
                    .eq(SqlReviewRequest::getStatus, ReviewStatus.APPROVED)
                    .set(SqlReviewRequest::getStatus, ReviewStatus.FAILED)
                    .set(SqlReviewRequest::getErrorMessage,
                            "批准后长时间无法投递到消息队列，已停止重试。"
                                    + "请确认 RabbitMQ 正常后重新提交这条工单")
                    .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));
            if (affected == 1) {
                log.error("工单批准后超过 {} 小时仍未投递成功，已放弃重试。reviewId={}",
                        stuckApprovedMaxHours, request.getId());
            } else {
                // 两个分支都要记：只记成功那一支的话，故障复盘时看不出"是谁先动的"——
                // 是补偿放弃时工单已经不在 APPROVED 了，还是根本没轮到它
                log.info("放弃重投时工单状态已变化，跳过。reviewId={}", request.getId());
            }
            return;
        }

        // 条件更新不是必需的（重复投递本身无害），这里也不改状态 ——
        // 保持 APPROVED 才是准确的状态描述：批了，还没执行。
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.REVIEW_QUEUE, request.getId(),
                    new CorrelationData("review-retry-" + request.getId()));
            log.warn("工单批准后 {} 分钟仍未被执行，补偿任务重新投递。reviewId={}",
                    stuckApprovedMinutes, request.getId());
        } catch (AmqpException e) {
            // 包一层只是为了让上面的日志能区分「投递失败」和「意外异常」；
            // 真正的重试是下一轮定时任务，不在这里就地重试。
            throw e;
        }
    }

    /** EXECUTING 卡住 → 标记失败，并明确告诉人「结果未知」。 */
    private void failStuckExecuting() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(stuckExecutingMinutes);

        for (SqlReviewRequest request : selectStuck(ReviewStatus.EXECUTING, deadline)) {
            // 条件更新加一道保险：只有<b>仍然</b>停在 EXECUTING 才改。
            // 不用 updateById 是因为「查出来 → 改 → 写回」中间有时间窗，
            // 消费者可能刚好写完了 SUCCESS，那样会把 EXECUTED 覆盖成 FAILED。
            int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                    .eq(SqlReviewRequest::getId, request.getId())
                    .eq(SqlReviewRequest::getStatus, ReviewStatus.EXECUTING)
                    .set(SqlReviewRequest::getStatus, ReviewStatus.FAILED)
                    .set(SqlReviewRequest::getErrorMessage,
                            "执行过程中断（进程重启或数据库连接异常）。这条 SQL 是否已经生效"
                                    + "无法确定，请先核对数据再决定是否重新提交 —— "
                                    + "系统不会自动重跑写语句")
                    // 显式写 updated_at：卡死判定和它必须是同一个时钟，
                    // 不能一边用 JVM 的 now() 比、一边指望数据库的 ON UPDATE 写
                    .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));

            if (affected == 1) {
                log.error("工单长时间停留在 EXECUTING，已标记为失败，需人工核对数据。reviewId={}, 阈值={}分钟",
                        request.getId(), stuckExecutingMinutes);
            } else {
                log.info("工单在补偿时状态已变化（可能刚被执行完），跳过。reviewId={}", request.getId());
            }
        }
    }

    /** 取处于某状态、且 updated_at 早于 before 的工单，带上限。 */
    private List<SqlReviewRequest> selectStuck(String status, LocalDateTime before) {
        IPage<SqlReviewRequest> page = reviewMapper.selectPage(
                new Page<>(1, BATCH_SIZE),
                new LambdaQueryWrapper<SqlReviewRequest>()
                        .eq(SqlReviewRequest::getStatus, status)
                        .lt(SqlReviewRequest::getUpdatedAt, before)
                        .orderByAsc(SqlReviewRequest::getId));

        if (page.getTotal() > BATCH_SIZE) {
            log.warn("状态为 {} 的卡死工单有 {} 条，本轮只处理前 {} 条，剩下的等下一轮",
                    status, page.getTotal(), BATCH_SIZE);
        }
        return page.getRecords();
    }
}
