package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
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

    /**
     * 单条工单 SQL 的执行超时（秒）。
     *
     * <p>比查询链路的 30 秒宽 —— 工单本来就是给「批量订正」用的；
     * 但<b>必须有</b>：它用的是和数据源同一个连接池（上限 5），
     * 一条挂住的 UPDATE 会慢慢把池子占满，把正常查询一起拖垮。
     *
     * <p>⚠️ 这个值必须<b>明显小于</b> {@code review.stuck-executing-minutes}
     * （卡死判定阈值），否则补偿任务会把一个仍在正常执行的工单判成卡死。
     */
    @Value("${review.query-timeout-seconds:120}")
    private int queryTimeoutSeconds;

    private final SqlReviewRequestMapper reviewMapper;
    private final DbSourceService dbSourceService;
    private final ConnectionManager connectionManager;
    private final SqlPermissionService sqlPermissionService;

    public SqlReviewConsumer(SqlReviewRequestMapper reviewMapper,
                             DbSourceService dbSourceService,
                             ConnectionManager connectionManager,
                             SqlPermissionService sqlPermissionService) {
        this.reviewMapper = reviewMapper;
        this.dbSourceService = dbSourceService;
        this.connectionManager = connectionManager;
        this.sqlPermissionService = sqlPermissionService;
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

            long affectedRows = doExecute(dbSource, request.getSqlText());

            finish(reviewId, ReviewStatus.EXECUTED, affectedRows, null);

            log.info("工单执行成功。reviewId={}, sqlType={}, affectedRows={}",
                    reviewId, validation.sqlType(), affectedRows);
        } catch (Exception e) {
            log.error("工单执行失败。reviewId={}", reviewId, e);

            // 只取 message 不取堆栈：error_message 是给用户看的字段，堆栈在日志里
            finish(reviewId, ReviewStatus.FAILED, null, brief(e.getMessage()));
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
     * 真正把 SQL 发到业务库上。
     *
     * <p>⚠️ 这里用 {@code executeUpdate}，<b>不是</b>查询链路那个 {@code execute}。
     * 差别在返回值：工单需要记录「改了多少行」，
     * 而 {@code execute} 只告诉你「有没有结果集」，影响行数还得再取一次。
     * 语义上也更准确 —— 这条路径上本来就不该出现查询。
     */
    private long doExecute(DbSource dbSource, String sql) throws SQLException {
        try (Connection connection = connectionManager.getConnection(dbSource);
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(queryTimeoutSeconds);
            return statement.executeUpdate(sql);
        }
    }

    private String brief(String message) {
        if (message == null) {
            return "执行失败，但数据库驱动没有给出原因";
        }
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }
}
