package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.mapper.SqlReviewUndoMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.SqlUndoRecorder;
import com.example.sqlquery.entity.SqlReviewUndo;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.util.SqlValidateUtil;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * 工单执行消费者 —— 审批通过之后，真正把那条 SQL 发到数据库上。
 *
 * <h2>这是整个平台上唯一一条「执行人工提交的写语句」的路径</h2>
 *
 * <p>它和 {@link ExportConsumer} 结构上同构（认领 → 干活 → 写回结果 → ack），
 * 但有两处<b>方向完全相反</b>的决定，都来自「写」和「读」的性质差别：
 *
 * <h2>一、执行前<b>重新跑一遍四道防线</b></h2>
 *
 * <p>提交工单时已经校验过一次了。为什么执行时还要再来一次？
 *
 * <ol>
 *   <li><b>提交和执行之间可能隔了很久。</b>数据源的方言可能被改过，
 *       reviewable-types 配置可能被收窄过。按「提交时合法」去执行，
 *       等于让一条<b>已经被收回授权</b>的语句照常跑。</li>
 *   <li><b>更要紧的：执行路径不能依赖「上游已经检查过」这个假设。</b>
 *       如果检查只发生在提交处，那么任何往 sql_review_request 里插了一行的东西
 *       （以后的运维工具、数据订正脚本、一个有 bug 的新入口）都能绕过全部检查。
 *       在执行处再查一遍，这一行数据才只是「一个申请」，
 *       而不是「一份已经生效的通行证」。</li>
 * </ol>
 *
 * <p>⚠️ 但必须说清它的边界：重复校验不等于「更安全」，
 * 真正的兜底<b>始终是数据库账号的权限</b>。它防的是「代码里的漏洞」，
 * 防不了「拿着这个账号直接连库的人」。
 *
 * <h2>二、执行失败<b>绝不自动重试</b></h2>
 *
 * <p>导出失败了重跑一次没有副作用；写语句不是 ——
 * {@code UPDATE t SET n = n + 1} 跑两次的结果和跑一次完全不同。
 *
 * <p>所以 EXECUTING 卡死时（进程被 kill、连接断开），补偿任务把它标成 FAILED
 * 并写明「结果未知，请人工核对」，而<b>不是</b>重新投递。
 * 见 {@link SqlReviewRecoveryJob}。
 */
@Slf4j
@Component
public class SqlReviewConsumer {

    // 注：单条工单 SQL 的执行超时（review.query-timeout-seconds）现在由
    // SqlUndoRecorder 持有 —— 因为「取像 + 执行」必须在同一个连接上完成，
    // 执行那一步已经搬到那里去了。同一件事配两个地方，早晚有人只改一个。

    private final SqlReviewRequestMapper reviewMapper;
    private final DbSourceService dbSourceService;
    private final ConnectionManager connectionManager;
    private final SqlPermissionService sqlPermissionService;
    private final SqlUndoRecorder undoRecorder;
    private final SqlReviewUndoMapper undoMapper;
    private final SqlPlatformMetrics metrics;

    public SqlReviewConsumer(SqlReviewRequestMapper reviewMapper,
                             DbSourceService dbSourceService,
                             ConnectionManager connectionManager,
                             SqlPermissionService sqlPermissionService,
                             SqlUndoRecorder undoRecorder,
                             SqlReviewUndoMapper undoMapper,
                             SqlPlatformMetrics metrics) {
        this.reviewMapper = reviewMapper;
        this.dbSourceService = dbSourceService;
        this.connectionManager = connectionManager;
        this.sqlPermissionService = sqlPermissionService;
        this.undoRecorder = undoRecorder;
        this.undoMapper = undoMapper;
        this.metrics = metrics;
    }

    /**
     * 消费入口。ack 策略与导出一致，逐条理由见 {@link ExportConsumer#handle}。
     */
    @RabbitListener(queues = RabbitConfig.REVIEW_QUEUE, ackMode = "MANUAL")
    public void handle(Long reviewId, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (!tryClaim(reviewId)) {
                // 重复投递 / 已经执行过 / 已经被驳回。
                // MQ 是「至少一次」投递，同一条消息出现两次是正常情况，不是异常。
                log.info("工单不处于可执行状态，跳过本次投递（幂等保护）。reviewId={}", reviewId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            execute(reviewId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("工单执行结果未能落库，投递到死信队列。reviewId={}, dlq={}",
                    reviewId, RabbitConfig.REVIEW_DLQ, e);
            // requeue=false：进死信队列等人工处理。
            // 用 true 的话，如果是持续性故障，消息会在队列里无限打转。
            // ⚠️ 这里**更不能**靠重新入队来「重试执行」—— 见类注释第二节。
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 原子认领：只有 {@link ReviewStatus#APPROVED} 才能被置为 {@link ReviewStatus#EXECUTING}。
     *
     * <p>用条件更新而不是「先查状态再判断」—— 后者两个消费者可能同时读到 APPROVED，
     * 然后<b>一起执行同一条 UPDATE</b>。这里的代价不是重复劳动，是重复改数据。
     */
    boolean tryClaim(Long reviewId) {
        int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getId, reviewId)
                .eq(SqlReviewRequest::getStatus, ReviewStatus.APPROVED)
                .set(SqlReviewRequest::getStatus, ReviewStatus.EXECUTING)
                // ⚠️ 显式写 updated_at，不要指望表上的 ON UPDATE CURRENT_TIMESTAMP。
                //
                // 那张表的时间戳有**两个来源**：INSERT / updateById 走的是
                // MyMetaObjectHandler（JVM 时钟），而 update(null, wrapper) 因为
                // entity 为 null 不触发自动填充，只能落到 MySQL 的 ON UPDATE（数据库时钟）。
                // 补偿任务却用 LocalDateTime.now()（JVM 时钟）去比 —— 一旦容器 TZ
                // 和数据库时区不一致，卡死判定就会整体偏掉（该失败的永远不失败，
                // 或者刚认领就被判超时）。**同一列必须只有一个写时钟。**
                .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));
        return affected == 1;
    }

    /**
     * 干活 + 把结果写回工单。
     *
     * <p>业务失败不往外抛（写 error_message 置 FAILED 即可）——
     * SQL 跑挂了是<b>业务结果</b>，重投一万次还是挂，而且每次都会真的再跑一遍那条 SQL。
     * 只有「连结果都写不进数据库」才抛出去，交给 {@link #handle} 进死信队列。
     */
    void execute(Long reviewId) {
        SqlReviewRequest request = reviewMapper.selectById(reviewId);
        if (request == null) {
            log.warn("工单不存在，跳过。reviewId={}", reviewId);
            return;
        }

        // ⭐ 本次的写语句**到底有没有生效**。两种失败对"要不要去核对数据"的答案相反：
        //     false → 取像或语句本身失败，事务已回滚，什么都没执行
        //     true  → 语句已提交生效，只是结果没写回工单
        boolean executed = false;

        try {
            // ⚠️ 用 getEnabledByIdAndUserId（要求启用），不是 getByIdAndUserId。
            //
            // 审批可能是几小时前做的，这期间数据源可能已经被停用。
            // 「停用」必须对<b>所有</b>链路生效 —— 包括一条排了很久队、刚刚才轮到执行的工单。
            // 如果这里图省事用不校验启用的那个方法，停用就成了半个摆设。
            DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(
                    request.getUserId(), request.getDataSourceId());

            SqlDialect dialect = SqlDialect.fromType(dbSource.getType());

            // 见类注释第一节：执行前重新走一遍全部防线
            SqlValidateUtil.ValidationResult validation = SqlValidateUtil.validate(
                    request.getSqlText(), sqlPermissionService.getReviewableTypes(), dialect);

            // 受理范围可以配置，但执行方式只有一种：不返回结果集的写语句。
            // 配置里若加了 SELECT，提交时会被受理、执行时在这里明确失败 ——
            // 而不是抛一句驱动层的 "Can not issue SELECT via executeUpdate()" 让人去猜。
            if (validation.sqlType() == SqlType.SELECT) {
                throw new BusinessException("工单只执行写语句，这条是查询语句，请到查询页执行");
            }

            // ⭐ 带留存的执行：执行前后各取一次行镜像，并生成反向 SQL。
            //    取像与写入在**同一个事务**里（见 SqlUndoRecorder），
            //    所以「改了但没留痕」和「留了痕但没改」都不会发生。
            SqlUndoRecorder.WriteResult result = undoRecorder.executeWithUndo(
                    dbSource, request.getSqlText());
            // ⭐ 从这一刻起，业务库上的写**已经提交**了 —— 后面再失败也不能说"什么都没做"
            executed = true;

            persistUndo(reviewId, result.capture());

            finish(reviewId, ReviewStatus.EXECUTED, result.affectedRows(), null);

            log.info("工单执行成功。reviewId={}, sqlType={}, affectedRows={}, undo={}",
                    reviewId, validation.sqlType(), result.affectedRows(),
                    result.capture().status());
        } catch (Exception e) {
            String why = executed
                    ? "本次写语句已生效，但结果未能写回工单，请先核对数据："
                    : "本次未执行任何语句（已回滚）：";
            log.error("工单执行失败。reviewId={}, executed={}", reviewId, executed, e);

            if (!executed) {
                // ⭐ 三态里的 FAILED 要**真的写下来**：
                //    在这之前它是个"永远不出现的状态"，任何按 capture_status='FAILED'
                //    写的巡检/告警静默地永远不会触发。
                //    ⚠️ executed=true 时**不能**覆盖 —— 那时留痕可能已经成功落库了，
                //    把一条好的 CAPTURED 记录改写成 FAILED 等于亲手毁掉回滚依据。
                persistUndo(reviewId, SqlUndoRecorder.UndoCapture.failed(why + brief(e.getMessage())));
            }

            // 只取 message 不取堆栈：error_message 是给用户看的字段，堆栈在日志里
            finish(reviewId, ReviewStatus.FAILED, null, why + brief(e.getMessage()));
        }
    }

    /**
     * 把执行结果写回工单。
     *
     * <h2>为什么必须是<b>条件更新</b>，不能是 {@code updateById}</h2>
     *
     * <p>认领用的是条件更新（APPROVED → EXECUTING），但最早的回写是
     * {@code updateById} —— 按主键整行覆盖，WHERE 里<b>没有</b>那个认领令牌。
     * 于是出现一条很隐蔽的竞态：
     *
     * <ol>
     *   <li>消费者认领了工单，正在跑那条慢 UPDATE；</li>
     *   <li>{@link SqlReviewRecoveryJob} 认为它卡死了（阈值配置不当、或者时间戳对不上），
     *       把它标成 FAILED 并写下「是否已生效无法确定，请先核对数据」；</li>
     *   <li>消费者跑完，{@code updateById} 把状态盖回 EXECUTED ——
     *       <b>而那句告警还留在 error_message 里</b>
     *       （MyBatis-Plus 默认的 NOT_NULL 策略下，把字段设成 null 根本不会进 SET 子句）。</li>
     * </ol>
     *
     * <p>最终用户在详情页看到的是「执行成功」配一条「请核对数据」的错误提示 ——
     * 一条已经生效的写语句，配着一条自相矛盾的审计记录。
     *
     * <p>改成条件更新之后，全链路只剩这一处不是原子迁移，现在补上了：
     * 谁先动谁生效，后来者写不进去，并且<b>一定会留下一条 ERROR 日志</b>让人去查。
     *
     * <p>⚠️ 注意 {@code set(...)} 是显式写列，{@code null} 也会进 SET 子句 ——
     * 这正是成功时要<b>清掉</b>上次失败留下的 error_message 所需要的。
     */
    private void finish(Long reviewId, String status, Long affectedRows, String errorMessage) {
        int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getId, reviewId)
                // 认领令牌：只有"仍然是我认领的那条 EXECUTING"才允许写回
                .eq(SqlReviewRequest::getStatus, ReviewStatus.EXECUTING)
                .set(SqlReviewRequest::getStatus, status)
                .set(SqlReviewRequest::getAffectedRows, affectedRows)
                .set(SqlReviewRequest::getErrorMessage, errorMessage)
                .set(SqlReviewRequest::getExecutedAt, LocalDateTime.now())
                .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));

        if (affected == 0) {
            log.error("工单已不再处于 EXECUTING，本次执行结果未落库，需人工核对。"
                    + "reviewId={}, 本次结果={}", reviewId, status);
        }
    }

    /**
     * 把前镜像 / 反向 SQL 落库。
     *
     * <h2>⚠️ 为什么这一步失败<b>不能</b>把工单标成 FAILED</h2>
     *
     * <p>调用它的时候，<b>业务库上的写语句已经提交了</b>
     * （{@link SqlUndoRecorder} 在一个事务里完成了取像 + 执行 + 提交）。
     * 此时如果把异常抛出去，工单会被标成 FAILED —— 而<b>数据其实已经改了</b>。
     * 那是最坏的一种状态：<b>审批人以为没生效，实际生效了。</b>
     *
     * <p>所以这里吞掉异常、但<b>绝不静默</b>：把反向 SQL 原样打进 ERROR 日志。
     * 留痕丢在数据库里还能从日志里捞回来，反过来就不行了。
     */
    private void persistUndo(Long reviewId, SqlUndoRecorder.UndoCapture capture) {
        SqlReviewUndo undo = new SqlReviewUndo();
        undo.setReviewId(reviewId);
        undo.setCaptureStatus(capture.status());
        undo.setReason(capture.reason());
        undo.setTableName(capture.tableName());
        undo.setRowCount(capture.rowCount());
        undo.setBeforeJson(capture.beforeJson());
        undo.setAfterJson(capture.afterJson());
        undo.setInverseSql(capture.inverseSql());
        undo.setCreatedAt(LocalDateTime.now());

        try {
            // ⚠️ **先 insert，不要先 delete。**
            //
            // 原来写的是 deleteById → insert（目的是让重投时能覆盖）。
            // 但那样有个真实的坏结果：**insert 一失败，连上一笔留痕也一起没了** ——
            // 把"少了一条新记录"放大成"回滚依据全丢了"。
            // 改成先 insert 之后，第一次执行就是**一条原子 insert**，失败时什么都没破坏；
            // 只有真的撞主键（同一工单被重复执行）才走覆盖分支。
            undoMapper.insert(undo);
        } catch (DuplicateKeyException e) {
            // 同一工单被重复执行（重投）：用新的一笔覆盖旧的
            try {
                undoMapper.deleteById(reviewId);
                undoMapper.insert(undo);
            } catch (Exception e2) {
                undoPersistFailed(reviewId, capture, e2);
            }
        } catch (Exception e) {
            undoPersistFailed(reviewId, capture, e);
        }
    }

    /**
     * 留存失败：计数 + 把反向 SQL 打进日志。
     *
     * <p>这里**不能只是记一条日志** —— 那条日志是回滚依据的最后一个落脚点，
     * 而日志会轮转。所以必须留一个可告警的计数器（{@code review.undo.persist.failed}），
     * "持续大于 0"就意味着**有一批工单执行过、却没有回滚依据**。
     */
    private void undoPersistFailed(Long reviewId, SqlUndoRecorder.UndoCapture capture, Exception e) {
        metrics.recordUndoPersistFailed();
        log.error("工单执行结果未能留存（数据已改动，反向 SQL 见本条日志）。reviewId={}, status={}",
                reviewId, capture.status(), e);
        if (capture.inverseSql() != null && !capture.inverseSql().isEmpty()) {
            log.error("未落库的反向 SQL（reviewId={}）：\n{}", reviewId, capture.inverseSql());
        }
    }

    private String brief(String message) {
        if (message == null) {
            return "执行失败，但数据库驱动没有给出原因";
        }
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }
}
