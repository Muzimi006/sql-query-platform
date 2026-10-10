package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.util.RabbitPublishConfirmer;
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

    /**
     * 等这条重投消息的确认最多等多久。
     *
     * <p>正常情况下确认在毫秒级回来；给 5 秒是为了容忍 Broker 抖动。
     * <b>等不到就按「没送出去」处理</b>（不刷新 updated_at、下一轮再投），理由见
     * {@link com.example.sqlquery.util.RabbitPublishConfirmer} 的类注释。
     */
    @Value("${review.recovery-publish-confirm-timeout-ms:5000}")
    private long publishConfirmTimeoutMs;

    private final SqlReviewRequestMapper reviewMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SqlPlatformMetrics metrics;
    private final RabbitPublishConfirmer publishConfirmer;

    public SqlReviewRecoveryJob(SqlReviewRequestMapper reviewMapper,
                                RabbitTemplate rabbitTemplate,
                                SqlPlatformMetrics metrics,
                                RabbitPublishConfirmer publishConfirmer) {
        this.reviewMapper = reviewMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.publishConfirmer = publishConfirmer;
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
        // ⚠️ 放弃判定用 **reviewedAt**，不是 updatedAt。
        //
        // reviewedAt 是"审批通过的那一刻"，写一次之后再没被动过；
        // 而 updatedAt 会被下面"重投成功"那一步刷新（那是为了让重投间隔生效）。
        // 如果这里也用 updatedAt，重投会不断把计时往后推，
        // **放弃条件就永远不成立** —— 一个 Broker 长期不可用的环境里，
        // 工单会一直重投到天荒地老，而不是在 24 小时后停下来。
        LocalDateTime approvedAt = request.getReviewedAt() != null
                ? request.getReviewedAt()
                : request.getUpdatedAt();   // 兜底：老数据可能没有 reviewedAt
        if (approvedAt != null && approvedAt.isBefore(giveUpBefore)) {
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

        CorrelationData correlationData = new CorrelationData("review-retry-" + request.getId());

        try {
            rabbitTemplate.convertAndSend(RabbitConfig.REVIEW_QUEUE, request.getId(), correlationData);
        } catch (AmqpException e) {
            // 投递都没成功，就不要动 updated_at —— 下一轮早点重试更好。
            // 包一层只是为了让上面的日志能区分「投递失败」和「意外异常」；
            // 真正的重试是下一轮定时任务，不在这里就地重试。
            throw e;
        }

        // ⭐⭐ 还必须**等到 Broker 的确认**才算投递成功。
        //
        // 为什么：队列满了以后（本项目配的是 x-overflow=reject-publish-dlx），
        // Broker 的做法是**异步否定确认**，而不是让 convertAndSend 抛异常 ——
        // 也就是说：投递其实失败了，上面那一行却照样平静返回。
        // 以前这里就直接往下走、把 updated_at 推到了现在，于是
        // **下一轮扫描会认为这条工单刚被处理过**，重投被推迟一轮又一轮，
        // 最坏情况是一直拖到 24 小时的放弃时限 —— 而真正的原因只是队列曾经满过。
        if (!publishConfirmer.awaitConfirmed(correlationData, publishConfirmTimeoutMs,
                SqlPlatformMetrics.LINK_REVIEW)) {
            throw new AmqpException("重投未能取得 Broker 确认（队列满 / Broker 抖动），本轮不刷新 updated_at");
        }

        // ⭐⭐ 投递成功之后必须把 updated_at 推到现在，**否则重投会被无限放大**。
        //
        // 为什么：selectStuck 的选中条件是 updated_at < now - stuckApprovedMinutes（默认 10 分钟），
        // 而 cron 周期默认是 **5 分钟** —— 比阈值还短。
        // 重投时不刷新 updated_at 的话，下一轮（5 分钟后）这一行**仍然满足条件**，
        // 于是被再次投递……一条卡住的工单 24 小时内最多被重投 **288 次**。
        //
        // 重复投递本身是安全的（消费者条件认领，多余的会被 ack 掉），
        // 但积压时这会实打实地**加剧队列拥堵**，还会把日志刷满。
        // 刷新之后，重投间隔就等于 stuckApprovedMinutes（10 分钟），频率降到 1/2。
        //
        // ⚠️ 状态仍然是 APPROVED —— 那才是准确的状态描述（批了，还没执行）。
        //    这一条更新只动 updated_at。
        reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getId, request.getId())
                .eq(SqlReviewRequest::getStatus, ReviewStatus.APPROVED)
                .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));

        // ⭐ 计数器："一批工单被反复重投"在指标上要看得见（以前只有 WARN 日志）
        metrics.recordRecoveryFailed(SqlPlatformMetrics.LINK_REVIEW);
        log.warn("工单批准后 {} 分钟仍未被执行，补偿任务重新投递。reviewId={}",
                stuckApprovedMinutes, request.getId());
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
                // ⭐ 这一条尤其要计数：它意味着"一条写语句被补偿判失败、结果未知"，
                //    批量发生时只靠日志是看不见规模的。
                metrics.recordRecoveryFailed(SqlPlatformMetrics.LINK_REVIEW);
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
