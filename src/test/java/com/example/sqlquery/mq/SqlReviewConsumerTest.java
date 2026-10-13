package com.example.sqlquery.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.domain.ReviewStatus;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.domain.UndoStatus;
import com.example.sqlquery.mapper.SqlReviewUndoMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.service.SqlUndoRecorder;
import com.example.sqlquery.infra.ConnectionManager;
import com.example.sqlquery.util.SqlType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单消费者的行为。
 *
 * <h2>这里守的是全项目<b>唯一</b>一条「执行人工提交的写语句」的路径</h2>
 *
 * <p>四条最要紧的性质：
 * <ol>
 *   <li><b>幂等</b>：MQ 是「至少一次」投递，同一条消息出现两次是正常的。
 *       认领必须用条件更新，重复投递时<b>一条 SQL 都不能再跑</b>。</li>
 *   <li><b>回写也必须是条件更新</b>：最早用的是 {@code updateById}（按主键整行覆盖），
 *       于是补偿任务判的 FAILED 会被消费者盖回 EXECUTED，
 *       而那句「请核对数据」还留在 error_message 里 ——
 *       「执行成功」配一条告警，是一条自相矛盾的审计记录。</li>
 *   <li><b>执行前重新校验</b>：提交时查过不算数。执行路径不能依赖「上游已经检查过」
 *       这个假设，否则任何往工单表里插一行的东西都能绕过全部防线。</li>
 *   <li><b>时间戳只能有一个时钟</b>：认领显式写 updated_at，
 *       否则线上是「数据库时钟写、JVM 时钟读」，容器 TZ 一变卡死判定就整体偏掉。</li>
 * </ol>
 */
class SqlReviewConsumerTest {

    private SqlReviewRequestMapper reviewMapper;
    private DbSourceService dbSourceService;
    private ConnectionManager connectionManager;
    private SqlPermissionService permissionService;
    private SqlReviewConsumer consumer;

    private Connection connection;
    private Statement statement;
    private SqlUndoRecorder undoRecorder;
    private SqlReviewUndoMapper undoMapper;
    private SqlPlatformMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlReviewRequest.class);

        reviewMapper = Mockito.mock(SqlReviewRequestMapper.class);
        dbSourceService = Mockito.mock(DbSourceService.class);
        connectionManager = Mockito.mock(ConnectionManager.class);
        permissionService = Mockito.mock(SqlPermissionService.class);

        // ⭐ 「取像 + 执行」已经搬到 SqlUndoRecorder 里了（它需要真实 JDBC 元数据，
        //    不是 mock 出来的 connection 能给的）—— 所以这里把它整个 mock 掉，
        //    本用例只验消费者自己的逻辑：认领 → 执行 → 留存 → 回写 → ack/nack。
        //    SqlUndoRecorder 自己的行为由 SqlUndoRecorderTest 和集成用例覆盖。
        undoRecorder = Mockito.mock(SqlUndoRecorder.class);
        undoMapper = Mockito.mock(SqlReviewUndoMapper.class);
        metrics = Mockito.mock(SqlPlatformMetrics.class);

        consumer = new SqlReviewConsumer(reviewMapper, dbSourceService, connectionManager,
                permissionService, undoRecorder, undoMapper, metrics);

        // 默认：执行成功、改 1 行、留存成功。个别用例再按需覆盖
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlUndoRecorder.WriteResult(1, captured(1)));

        connection = Mockito.mock(Connection.class);
        statement = Mockito.mock(Statement.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any())).thenReturn(connection);

        Mockito.when(permissionService.getReviewableTypes())
                .thenReturn(Set.of(SqlType.INSERT, SqlType.UPDATE, SqlType.DELETE));
        Mockito.when(dbSourceService.getEnabledByIdAndUserId(1L, 2L)).thenReturn(dbSource());
    }

    /** 构造一个「已留存」的 UndoCapture，给桩用。 */
    private static SqlUndoRecorder.UndoCapture captured(int rows) {
        return new SqlUndoRecorder.UndoCapture(UndoStatus.CAPTURED, null, "t_user", rows,
                "[]", "[]", "UPDATE t_user SET nickname = 'old' WHERE id = 1;");
    }

    private DbSource dbSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(2L);
        dbSource.setUserId(1L);
        dbSource.setType("MYSQL");
        return dbSource;
    }

    private SqlReviewRequest ticket(String sql) {
        SqlReviewRequest request = new SqlReviewRequest();
        request.setId(1L);
        request.setUserId(1L);
        request.setDataSourceId(2L);
        request.setSqlText(sql);
        request.setStatus(ReviewStatus.EXECUTING);
        return request;
    }

    private LambdaUpdateWrapper<SqlReviewRequest> captureUpdate() {
        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());
        return (LambdaUpdateWrapper<SqlReviewRequest>) captor.getValue();
    }

    // ------------------------------------------------------------------
    // 认领
    // ------------------------------------------------------------------

    @Test
    @DisplayName("条件更新影响 1 行 = 认领成功；影响 0 行 = 绝不能再执行一次")
    void claimIsConditional() {
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        assertTrue(consumer.tryClaim(1L));

        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);
        assertFalse(consumer.tryClaim(1L), "影响 0 行时必须放弃，否则同一条 UPDATE 会被执行两次");
    }

    @Test
    @DisplayName("认领的 WHERE 必须要求当前状态是 APPROVED")
    void claimMustRequireApprovedStatus() {
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);

        consumer.tryClaim(1L);

        assertTrue(captureUpdate().getSqlSegment().contains("status"),
                "状态条件必须在 WHERE 里");
    }

    @Test
    @DisplayName("认领必须显式写 updated_at —— 同一列不能有两个时钟")
    void claimMustWriteUpdatedAtExplicitly() {
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);

        consumer.tryClaim(1L);

        // 不显式写的话，这一列由 MySQL 的 ON UPDATE CURRENT_TIMESTAMP（数据库时钟）填，
        // 而补偿任务用的是 LocalDateTime.now()（JVM 时钟）。
        // 容器 TZ 和库时区不一致时，卡死判定会整体偏掉：
        // 该失败的永远不失败，或者一条刚认领的工单立刻被判超时
        assertTrue(captureUpdate().getSqlSet().contains("updated_at"),
                "必须显式写 updated_at，实际 SET：" + captureUpdate().getSqlSet());
    }

    @Test
    @DisplayName("重复投递：ack 掉消息，且绝不碰数据库")
    void duplicateDeliveryMustNotExecute() throws Exception {
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        consumer.handle(1L, channel, 42L);

        Mockito.verify(channel).basicAck(42L, false);
        Mockito.verify(connectionManager, Mockito.never()).getConnection(ArgumentMatchers.any());
        Mockito.verify(reviewMapper, Mockito.never()).selectById(ArgumentMatchers.anyLong());
    }

    // ------------------------------------------------------------------
    // 执行与回写
    // ------------------------------------------------------------------

    @Test
    @DisplayName("执行成功：写回 EXECUTED + 影响行数 + 执行时间，且带上认领令牌")
    void executeShouldRecordAffectedRows() throws Exception {
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlUndoRecorder.WriteResult(7, captured(3)));

        consumer.execute(1L);

        LambdaUpdateWrapper<SqlReviewRequest> wrapper = captureUpdate();
        Map<String, Object> params = wrapper.getParamNameValuePairs();

        assertTrue(wrapper.getSqlSet().contains("status"));
        assertTrue(wrapper.getSqlSet().contains("affected_rows"));
        assertTrue(wrapper.getSqlSet().contains("executed_at"));
        assertTrue(wrapper.getSqlSet().contains("updated_at"), "回写同样要显式写 updated_at");
        assertTrue(params.values().contains(ReviewStatus.EXECUTED), "实际参数：" + params.values());
        assertTrue(params.values().contains(7L), "影响行数必须落库，实际参数：" + params.values());

        // 执行必须真的交给了带留存的那条路径，而且**带的是数据库里存的那条 SQL**
        // （不是前端再传一次 —— 那是审批类系统最经典的 TOCTOU 漏洞）
        Mockito.verify(undoRecorder).executeWithUndo(ArgumentMatchers.any(),
                ArgumentMatchers.eq("update t_user set nickname = 'x' where id = 1"));
    }

    @Test
    @DisplayName("回写必须是条件更新：状态已不是 EXECUTING 时不能把结果盖回去")
    void writeBackMustCarryTheClaimToken() throws Exception {
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlUndoRecorder.WriteResult(1, captured(1)));
        // 0 行 = 补偿任务已经把它判成失败了
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);

        consumer.execute(1L);

        LambdaUpdateWrapper<SqlReviewRequest> wrapper = captureUpdate();

        // WHERE 里必须有认领令牌。少了它，补偿任务写下的 FAILED
        // 会被这里的 EXECUTED 盖掉，而那句「是否已生效无法确定」还留在 error_message 里
        assertTrue(wrapper.getSqlSegment().contains("status"),
                "回写的 WHERE 必须带认领令牌，实际：" + wrapper.getSqlSegment());

        // 成功时要把 error_message 设成 null —— 而 MyBatis-Plus 默认的 NOT_NULL 策略下，
        // 实体字段为 null 根本不会进 SET 子句，只有 set(...) 显式写列才会
        assertTrue(wrapper.getSqlSet().contains("error_message"),
                "必须显式写 error_message，否则上一次失败留下的告警永远清不掉，实际 SET："
                        + wrapper.getSqlSet());
    }

    @Test
    @DisplayName("执行前必须重新跑一遍防线：提交时合法不代表执行时仍然合法")
    void mustRevalidateAtExecutionTime() throws Exception {
        // 这条 SQL 在提交时就该被拦，但假设它因为某种原因（运维工具、数据订正脚本、
        // 一个有 bug 的新入口）进了工单表 —— 执行处必须自己再拦一次
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1 and sleep(1)"));

        consumer.execute(1L);

        Map<String, Object> params = captureUpdate().getParamNameValuePairs();
        String all = params.values().toString();
        assertTrue(all.contains(ReviewStatus.FAILED), "实际：" + all);
        assertTrue(all.contains("危险函数"), "实际：" + all);
        // 关键：根本没连数据库
        Mockito.verify(connectionManager, Mockito.never()).getConnection(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("数据源已停用：不执行，落 FAILED 并说明原因")
    void disabledDataSourceMustBlockExecution() throws Exception {
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(dbSourceService.getEnabledByIdAndUserId(1L, 2L))
                .thenThrow(new BusinessException("数据源不存在或已停用"));

        consumer.execute(1L);

        Map<String, Object> params = captureUpdate().getParamNameValuePairs();
        assertTrue(params.values().contains(ReviewStatus.FAILED));
        Mockito.verify(connectionManager, Mockito.never()).getConnection(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("工单里混进 SELECT：明确失败，而不是抛驱动层的怪话")
    void selectTicketMustFailWithAClearMessage() throws Exception {
        Mockito.when(permissionService.getReviewableTypes()).thenReturn(Set.of(SqlType.SELECT));
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(ticket("select * from t_user"));

        consumer.execute(1L);

        String all = captureUpdate().getParamNameValuePairs().values().toString();
        assertTrue(all.contains(ReviewStatus.FAILED), "实际：" + all);
        assertTrue(all.contains("写语句"), "实际：" + all);
    }

    @Test
    @DisplayName("⭐ 回写被拒（影响 0 行）时必须落一个计数器 —— 这是「数据已改但状态没写回」的唯一信号")
    void droppedWriteBackMustBeCounted() throws Exception {
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        // 认领成功、执行成功，但回写时状态已经不是 EXECUTING（补偿任务抢先了）
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);

        consumer.execute(1L);

        // 以前只有一条 ERROR 日志 —— 没人知道"有几条结果被丢弃了"
        Mockito.verify(metrics).recordResultDropped(SqlPlatformMetrics.LINK_REVIEW);
    }

    @Test
    @DisplayName("SQL 执行报错：落 FAILED + 原因，不外抛（重投一万次也还是失败）")
    void sqlFailureShouldBeRecordedNotThrown() throws Exception {
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenThrow(new java.sql.SQLException("Unknown column 'nickname'"));

        consumer.execute(1L);

        String all = captureUpdate().getParamNameValuePairs().values().toString();
        assertTrue(all.contains(ReviewStatus.FAILED));
        assertTrue(all.contains("Unknown column"), "实际：" + all);
    }

    @Test
    @DisplayName("结果写不进数据库时才 nack（requeue=false）进死信队列")
    void persistenceFailureShouldGoToDlq() throws Exception {
        // 第一次 update 是认领（成功），第二次是回写（失败）
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(1)
                .thenThrow(new RuntimeException("平台库不可用"));
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlUndoRecorder.WriteResult(1, captured(1)));

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        consumer.handle(1L, channel, 42L);

        Mockito.verify(channel).basicNack(42L, false, false);
        Mockito.verify(channel, Mockito.never()).basicAck(ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("⭐ 写语句绝不自动重试：落库失败进了死信，UPDATE 也只执行过一次")
    void writeStatementMustNeverBeRetried() throws Exception {
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(1)
                .thenThrow(new RuntimeException("平台库不可用"));
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("update t_user set nickname = 'x' where id = 1"));
        Mockito.when(undoRecorder.executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlUndoRecorder.WriteResult(1, captured(1)));

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        consumer.handle(1L, channel, 42L);

        // 导出链路（只读）在同样的故障下会退避重试，工单链路**刻意不会** ——
        // 因为重跑一次 UPDATE 就是再改一次数据。这条断言就是那条铁律的看门人：
        // 谁要是给这个消费者也加一个重试循环，这里会立刻红。
        // 至于"执行到一半"的工单，交给 SqlReviewRecoveryJob 判 EXECUTING 超时 → FAILED。
        Mockito.verify(undoRecorder, Mockito.times(1))
                .executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("⭐ ack 自己抛异常 ≠ 执行失败：写语句已经提交，不许谎报成「未落库」")
    void ackFailureMustNotBeReportedAsExecutionFailure() throws Exception {
        // 这条链路上跑的是一条**写语句**，所以这一条比导出侧更要紧：
        // ack 抛 IOException 被当成执行失败时，死信队列里会出现一条
        // 「工单执行失败、结果未能落库」，而工单状态其实是 EXECUTED、留痕也写成功了 ——
        // 数据没错，错的是那句话，而排查方向会从一开始就被带偏。
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(1);
        Mockito.when(reviewMapper.selectById(1L))
                .thenReturn(ticket("UPDATE t_user SET nickname = 'n' WHERE id = 1"));

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        Mockito.doThrow(new java.io.IOException("channel 已坏"))
                .when(channel).basicAck(42L, false);

        consumer.handle(1L, channel, 42L);

        // 语句只执行一次：ack 失败不构成「重跑一遍 UPDATE」的理由
        Mockito.verify(undoRecorder, Mockito.times(1))
                .executeWithUndo(ArgumentMatchers.any(), ArgumentMatchers.anyString());
        // 绝不能把一次已经生效的改动 nack 成死信
        Mockito.verify(channel, Mockito.never())
                .basicNack(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean());
        Mockito.verify(metrics).recordAckFailed(SqlPlatformMetrics.LINK_REVIEW);
    }

    @Test
    @DisplayName("工单被删掉了：什么都不写，也不报错")
    void missingTicketShouldBeANoOp() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(null);

        consumer.execute(1L);

        Mockito.verify(reviewMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }
}
