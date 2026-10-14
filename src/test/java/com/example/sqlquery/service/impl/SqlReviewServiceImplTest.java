package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.domain.ReviewStatus;
import com.example.sqlquery.domain.SqlOrigin;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.domain.UserRole;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.sql.SqlImpactAnalyzer;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.sql.SqlType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.example.sqlquery.common.PageConstants;
import com.example.sqlquery.mapper.SqlReviewUndoMapper;
import com.example.sqlquery.mq.RabbitConfig;

/**
 * SQL 审核工单服务的核心行为。
 *
 * <h2>它守的四个失败模式</h2>
 *
 * <ol>
 *   <li><b>「能申请」被误写成「能执行」</b>：提交工单时套用角色白名单的话，
 *       只有 SELECT 权限的 USER 连一条 UPDATE 的工单都提交不了 ——
 *       而"让没有写权限的人能申请写操作"正是工单存在的理由。
 *       这条被 {@link #submitMustNotConsultRoleWhitelist} 钉死。</li>
 *   <li><b>两个管理员同时审批</b>：必须用条件更新，只有一个能生效。</li>
 *   <li><b>驳回不写原因</b>：申请人不知道该改什么，只能原样再提交一遍。</li>
 *   <li><b>越权看别人的工单</b>：回答必须是「不存在」而不是「无权」。</li>
 * </ol>
 */
// ⚠️ 类级抑制的理由：这个类用 Mockito 的 ArgumentCaptor.forClass(...) 与匹配器 varargs，
//    二者在 Java 泛型下**必然**产生 unchecked 警告（框架 API 的固有形态，不是隐患）。
//    就地抑制，比在构建日志里留一屏黄更利于发现真问题。
@SuppressWarnings("unchecked")
class SqlReviewServiceImplTest {

    private SqlReviewRequestMapper reviewMapper;
    private DbSourceService dbSourceService;
    private SqlPermissionService permissionService;
    private SqlImpactAnalyzer impactAnalyzer;
    private RabbitTemplate rabbitTemplate;
    private SqlPlatformMetrics metrics;
    private com.example.sqlquery.mapper.SqlReviewUndoMapper undoMapper;
    private SqlReviewServiceImpl service;

    private static final Set<SqlType> REVIEWABLE =
            Set.of(SqlType.INSERT, SqlType.UPDATE, SqlType.DELETE);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlReviewRequest.class);

        reviewMapper = Mockito.mock(SqlReviewRequestMapper.class);
        dbSourceService = Mockito.mock(DbSourceService.class);
        permissionService = Mockito.mock(SqlPermissionService.class);
        impactAnalyzer = Mockito.mock(SqlImpactAnalyzer.class);
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        metrics = Mockito.mock(SqlPlatformMetrics.class);
        undoMapper = Mockito.mock(com.example.sqlquery.mapper.SqlReviewUndoMapper.class);

        service = new SqlReviewServiceImpl(
                reviewMapper, dbSourceService, permissionService, impactAnalyzer, rabbitTemplate, metrics,
                undoMapper);

        UserContext.set(1L);
        UserContext.setRole(UserRole.USER);

        Mockito.when(dbSourceService.getEnabledByIdAndUserId(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
                .thenReturn(dbSource());
        Mockito.when(permissionService.getReviewableTypes()).thenReturn(REVIEWABLE);
        Mockito.when(impactAnalyzer.analyze(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenReturn(new SqlImpactAnalyzer.ImpactAnalysis(
                        List.of("t_user"), 12345L, "预估扫描约 12345 行（这是 EXPLAIN 的扫描行数，不是最终会改动的行数）"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private DbSource dbSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(2L);
        dbSource.setUserId(1L);
        dbSource.setType("MYSQL");
        return dbSource;
    }

    private SqlReviewSubmitDTO submitDto(String sql) {
        SqlReviewSubmitDTO dto = new SqlReviewSubmitDTO();
        dto.setDataSourceId(2L);
        dto.setSql(sql);
        return dto;
    }

    private SqlReviewRequest existing(Long id, Long userId, String status) {
        SqlReviewRequest request = new SqlReviewRequest();
        request.setId(id);
        request.setUserId(userId);
        request.setStatus(status);
        request.setSqlText("update t_user set nickname = 'x' where id = 1");
        // 幂等命中时要比对载荷，所以这个夹具必须和默认的提交内容一致
        request.setDataSourceId(2L);
        return request;
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交走的是 reviewable-types，不是角色白名单 —— 否则 USER 根本申请不了写操作")
    void submitMustNotConsultRoleWhitelist() {
        service.submit(1L, submitDto("update t_user set nickname = 'x' where id = 1"));

        // 结构性断言：这条链路**不允许**去问"你的角色允许执行什么"。
        // 一旦有人把它改回 getAllowedTypes(role)，这条用例立刻变红。
        Mockito.verify(permissionService, Mockito.never()).getAllowedTypes(ArgumentMatchers.anyString());
        Mockito.verify(permissionService).getReviewableTypes();

        ArgumentCaptor<SqlReviewRequest> captor = ArgumentCaptor.forClass(SqlReviewRequest.class);
        Mockito.verify(reviewMapper).insert(captor.capture());
        SqlReviewRequest saved = captor.getValue();

        assertEquals(ReviewStatus.PENDING, saved.getStatus());
        assertEquals("UPDATE", saved.getSqlType());
        assertEquals(SqlOrigin.HUMAN, saved.getGeneratedBy(), "人工入口提交的一律记为 HUMAN");
        assertEquals(1L, saved.getUserId());
        assertEquals(2L, saved.getDataSourceId());
    }

    @Test
    @DisplayName("受理范围为空时全拒（fail-closed），而不是放行")
    void emptyReviewableTypesShouldFailClosed() {
        Mockito.when(permissionService.getReviewableTypes()).thenReturn(Set.of());

        assertThrows(BusinessException.class,
                () -> service.submit(1L, submitDto("update t_user set nickname = 'x' where id = 1")));
        Mockito.verify(reviewMapper, Mockito.never()).insert(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("提交 SELECT：回话必须是「这是查询，不用审批」，而不是「你没有权限」")
    void selectShouldBeRejectedWithAHelpfulMessage() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, submitDto("select * from t_user")));

        // 这条例外最关键的地方在措辞。原样转发底层那句「没有权限执行该类型SQL」，
        // 会让一个明明有查询权限的用户被告知「没权限」—— 把他引向完全错误的方向
        assertTrue(e.getMessage().contains("不需要走审核工单"),
                "提交查询语句时应当告诉他不用审批，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("提交 DDL：说明它走 DBA 流程，而不是含糊地拒绝")
    void ddlShouldBeRejectedWithItsOwnMessage() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, submitDto("drop table t_user")));

        assertTrue(e.getMessage().contains("DDL"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("DBA"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("危险函数在提交时就要被 AST 挡住 —— 可申请的是写语句，不是危险语句")
    void dangerousFunctionShouldBeRejectedAtSubmit() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, submitDto(
                        "update t_user set nickname = 'x' where id = 1 and sleep(1)")));

        assertTrue(e.getMessage().contains("危险函数"), "实际：" + e.getMessage());
        Mockito.verify(reviewMapper, Mockito.never()).insert(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("系统库同样拦得住：可申请不等于放开系统表")
    void systemSchemaShouldBeRejectedAtSubmit() {
        assertThrows(BusinessException.class,
                () -> service.submit(1L, submitDto(
                        "update mysql.user set authentication_string = 'x' where user = 'root'")));
    }

    @Test
    @DisplayName("影响面要落库：动了哪几张表 + 预估扫描行数 + 说明")
    void impactShouldBePersisted() {
        service.submit(1L, submitDto("update t_user set nickname = 'x' where id = 1"));

        ArgumentCaptor<SqlReviewRequest> captor = ArgumentCaptor.forClass(SqlReviewRequest.class);
        Mockito.verify(reviewMapper).insert(captor.capture());
        SqlReviewRequest saved = captor.getValue();

        assertEquals("t_user", saved.getInvolvedTables());
        assertEquals(12345L, saved.getEstimatedRows());
        assertTrue(saved.getEstimateNote().contains("扫描"),
                "预估必须写明是扫描行数，实际：" + saved.getEstimateNote());
    }

    @Test
    @DisplayName("EXPLAIN 失败不影响提交 —— 只是这条工单没有预估可参考")
    void impactFailureShouldNotBlockSubmit() {
        Mockito.when(impactAnalyzer.analyze(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenReturn(new SqlImpactAnalyzer.ImpactAnalysis(
                        List.of("t_user"), null, "预估失败：表不存在"));

        service.submit(1L, submitDto("update t_user set nickname = 'x' where id = 1"));

        ArgumentCaptor<SqlReviewRequest> captor = ArgumentCaptor.forClass(SqlReviewRequest.class);
        Mockito.verify(reviewMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getEstimatedRows());
        assertTrue(captor.getValue().getEstimateNote().contains("预估失败"));
    }

    @Test
    @DisplayName("同一个幂等键重复提交：返回已有工单，不报错、不新建")
    void duplicateTokenShouldReturnExistingTicket() {
        SqlReviewSubmitDTO dto = submitDto("update t_user set nickname = 'x' where id = 1");
        dto.setRequestToken("tok-1");

        Mockito.when(reviewMapper.insert(ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("uk_user_token"));
        Mockito.when(reviewMapper.selectOne(ArgumentMatchers.any()))
                .thenReturn(existing(77L, 1L, ReviewStatus.PENDING));

        var vo = service.submit(1L, dto);

        assertEquals(77L, vo.getId(), "重复提交应当返回已经存在的那一条，而不是报错");
    }

    @Test
    @DisplayName("唯一键冲突但查不到对应记录时，必须原样抛出，不能伪装成「重复提交」")
    void conflictWithoutMatchingTokenShouldPropagate() {
        SqlReviewSubmitDTO dto = submitDto("update t_user set nickname = 'x' where id = 1");
        dto.setRequestToken("tok-1");

        Mockito.when(reviewMapper.insert(ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("其它唯一键"));
        Mockito.when(reviewMapper.selectOne(ArgumentMatchers.any())).thenReturn(null);

        assertThrows(DuplicateKeyException.class, () -> service.submit(1L, dto));
    }

    @Test
    @DisplayName("AI 生成的工单：来源标记为 AI，且走完全相同的校验（危险函数照样拦）")
    void aiGeneratedSqlMustGoThroughTheSameChecks() {
        SqlReviewSubmitDTO dto = submitDto("update t_user set nickname = 'x' where id = 1");
        dto.setGenerationPrompt("把昵称改成 x");

        var vo = service.submitGenerated(1L, dto, "把昵称改成 x");

        ArgumentCaptor<SqlReviewRequest> captor = ArgumentCaptor.forClass(SqlReviewRequest.class);
        Mockito.verify(reviewMapper).insert(captor.capture());
        assertEquals(SqlOrigin.AI, captor.getValue().getGeneratedBy());
        assertEquals("把昵称改成 x", captor.getValue().getGenerationPrompt());
        assertEquals(ReviewStatus.PENDING, vo.getStatus(), "AI 生成的 SQL 也只是「待审批」，没有任何特权");

        // ⚠️ 这条才是重点：AI 的 SQL 走的是**同一个** validate，危险函数一道不少。
        //    如果哪天为了"提高 AI 成功率"给它开一条宽松路径，这行断言会立刻变红
        Mockito.verify(permissionService, Mockito.never()).getAllowedTypes(ArgumentMatchers.anyString());
        assertThrows(BusinessException.class, () -> service.submitGenerated(1L,
                submitDto("update t_user set nickname = load_file('/etc/passwd')"), "改昵称"));
    }

    // ------------------------------------------------------------------
    // 审批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批准：条件更新必须把「当前必须是 PENDING」写进 WHERE，而不是先查后判")
    void approveMustUseConditionalUpdate() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.approve(9L, 1L, "看过了，可以");

        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());

        LambdaUpdateWrapper<SqlReviewRequest> wrapper = (LambdaUpdateWrapper<SqlReviewRequest>) captor.getValue();
        String where = wrapper.getSqlSegment();
        String set = wrapper.getSqlSet();

        assertTrue(where.contains("status"), "WHERE 里必须带上状态条件，实际：" + where);
        assertTrue(set.contains("status"), "必须改状态，实际：" + set);
        assertTrue(set.contains("reviewer_id"), "必须记下审批人，实际：" + set);
        assertTrue(set.contains("reviewed_at"), "必须记下审批时间，实际：" + set);
    }

    @Test
    @DisplayName("两个管理员同时点通过：后一个必须收到明确提示，且不会重复投递执行")
    void concurrentApproveShouldOnlySucceedOnce() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        // 影响 0 行 = 在我"读出来"和"写回去"之间，另一个管理员已经批过了
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);
        UserContext.setRole(UserRole.ADMIN);

        BusinessException e = assertThrows(BusinessException.class, () -> service.approve(9L, 1L, null));

        assertTrue(e.getMessage().contains("已被处理"), "实际：" + e.getMessage());
        // 输的那一次绝不能发执行消息 —— 否则同一条 UPDATE 会被投递两次
        Mockito.verify(rabbitTemplate, Mockito.never())
                .convertAndSend(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                        ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    @Test
    @DisplayName("批准之后把执行消息投出去")
    void approveShouldEnqueueExecution() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.approve(9L, 1L, "ok");

        Mockito.verify(rabbitTemplate).convertAndSend(
                ArgumentMatchers.eq(com.example.sqlquery.mq.RabbitConfig.REVIEW_QUEUE),
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    @Test
    @DisplayName("Broker 挂了：审批照样算成功，不能把一个已经落库的决定报成失败")
    void brokerDownMustNotFailTheApproval() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        // ⚠️ 必须用 doThrow().when()：convertAndSend 的这个重载返回 void，
        //    写成 when(mock.voidMethod(...)).thenThrow(...) 是编译不过的
        Mockito.doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbitTemplate).convertAndSend(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                        ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        UserContext.setRole(UserRole.ADMIN);

        // 不抛异常：那次 UPDATE 已经提交了，审批**已经生效**。
        // 抛异常会让审批人以为没成功而再点一次，然后撞上「已被处理过」——
        // 用一个假的失败去描述一次真的成功，比什么都不说更糟。
        // 工单停在 APPROVED（准确的状态描述），由 SqlReviewRecoveryJob 重投。
        service.approve(9L, 1L, null);

        // ⭐ 但"不抛异常"不等于"这件事没发生" ——
        //    接口返回 200、工单老老实实停在 APPROVED，从外面看一切正常。
        //    只有这个持续大于 0 的计数器能把"Broker 出问题了"暴露出来。
        //    在它之前，唯一的痕迹是一条 ERROR 日志（代码里自己写着"应该配一个计数器"）。
        Mockito.verify(metrics).recordPublishFailure(SqlPlatformMetrics.LINK_REVIEW);
    }

    @Test
    @DisplayName("非管理员不能审批")
    void nonAdminMustNotApprove() {
        UserContext.setRole(UserRole.USER);

        BusinessException e = assertThrows(BusinessException.class, () -> service.approve(9L, 1L, null));
        assertTrue(e.getMessage().contains("管理员"), "实际：" + e.getMessage());
        Mockito.verify(reviewMapper, Mockito.never()).update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("驳回必须写原因 —— 否则申请人不知道该改什么")
    void rejectMustRequireComment() {
        UserContext.setRole(UserRole.ADMIN);

        assertThrows(BusinessException.class, () -> service.reject(9L, 1L, null));
        assertThrows(BusinessException.class, () -> service.reject(9L, 1L, "   "));
        Mockito.verify(reviewMapper, Mockito.never()).update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("驳回：落 REJECTED，且**不发**执行消息")
    void rejectShouldRecordAndNotExecute() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.reject(9L, 1L, "where 条件太宽，会改到全表");

        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("review_comment"));

        Mockito.verify(rabbitTemplate, Mockito.never())
                .convertAndSend(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                        ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    @Test
    @DisplayName("REJECTED 是终态，不是 FAILED —— 混在一起就统计不出「驳回了多少」")
    void rejectedIsTerminalAndDistinctFromFailed() {
        assertEquals("REJECTED", ReviewStatus.REJECTED);
        assertTrue(!ReviewStatus.FAILED.equals(ReviewStatus.REJECTED));
        assertTrue(!ReviewStatus.isOpen(ReviewStatus.REJECTED), "驳回之后不该再被轮询/补偿");
        assertTrue(!ReviewStatus.isOpen(ReviewStatus.EXECUTED));
        assertTrue(!ReviewStatus.isOpen(ReviewStatus.FAILED));
        assertTrue(ReviewStatus.isOpen(ReviewStatus.PENDING));
        assertTrue(ReviewStatus.isOpen(ReviewStatus.APPROVED));
        assertTrue(ReviewStatus.isOpen(ReviewStatus.EXECUTING));
    }

    // ------------------------------------------------------------------
    // 查看
    // ------------------------------------------------------------------

    @Test
    @DisplayName("看别人的工单：回答「工单不存在」，不能确认它存在")
    void othersTicketShouldLookNonexistent() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 99L, ReviewStatus.PENDING));
        UserContext.setRole(UserRole.USER);

        BusinessException e = assertThrows(BusinessException.class, () -> service.detail(1L, 1L));

        // 说「无权查看」等于确认了"这个 id 上确实有工单"，
        // 拿一串 id 去试就能数出全部工单量
        assertTrue(e.getMessage().contains("不存在"), "实际：" + e.getMessage());
        assertTrue(!e.getMessage().contains("权限"), "不能泄漏「它存在但你无权看」这个信息");
    }

    @Test
    @DisplayName("管理员可以看任何工单")
    void adminCanSeeAnyTicket() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 99L, ReviewStatus.PENDING));
        UserContext.setRole(UserRole.ADMIN);

        assertNotNull(service.detail(1L, 1L));
    }

    @Test
    @DisplayName("申请人可以看自己的工单")
    void ownerCanSeeOwnTicket() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));

        assertNotNull(service.detail(1L, 1L));
    }

    @Test
    @DisplayName("待审批队列只有管理员能看")
    void pendingQueueIsAdminOnly() {
        UserContext.setRole(UserRole.USER);

        BusinessException e = assertThrows(BusinessException.class, () -> service.pagePending(1, 10));
        assertTrue(e.getMessage().contains("管理员"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("分页 size 必须被夹到上限，防止一次请求把整张表捞进内存")
    void pageSizeMustBeClamped() {
        Mockito.when(reviewMapper.selectPage(
                        ArgumentMatchers.<com.baomidou.mybatisplus.extension.plugins.pagination.Page<SqlReviewRequest>>any(),
                        ArgumentMatchers.<Wrapper<SqlReviewRequest>>any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.pageMine(1L, 1, 100000);

        ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<SqlReviewRequest>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
        Mockito.verify(reviewMapper).selectPage(captor.capture(), ArgumentMatchers.any());
        assertEquals(com.example.sqlquery.common.PageConstants.MAX_PAGE_SIZE, captor.getValue().getSize());
    }

    // ------------------------------------------------------------------
    // 四眼原则：不能自己批自己的工单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不能审批自己提交的工单 —— 否则「另一个人看一眼」就白设了")
    void selfApprovalMustBeRejectedByDefault() {
        // 工单属于 9L，审批人也是 9L
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 9L, ReviewStatus.PENDING));
        UserContext.setRole(UserRole.ADMIN);

        BusinessException e = assertThrows(BusinessException.class, () -> service.approve(9L, 1L, null));

        assertTrue(e.getMessage().contains("另一位管理员"), "实际：" + e.getMessage());
        Mockito.verify(reviewMapper, Mockito.never()).update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
        // 更不能把执行消息投出去
        Mockito.verify(rabbitTemplate, Mockito.never())
                .convertAndSend(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object.class),
                        ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    @Test
    @DisplayName("只有一名管理员的团队可以显式打开自批开关")
    void selfApprovalAllowedWhenExplicitlyEnabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "allowSelfApprove", true);
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 9L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.approve(9L, 1L, null);

        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("驳回自己的工单要放行 —— 那本质上是「撤回」，禁止它只会让人被自己卡住")
    void selfRejectShouldBeAllowed() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 9L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.reject(9L, 1L, "提错了，撤回");

        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("审批必须显式写 updated_at，和补偿任务的判定用同一个时钟")
    void approveMustWriteUpdatedAtExplicitly() {
        Mockito.when(reviewMapper.selectById(1L)).thenReturn(existing(1L, 1L, ReviewStatus.PENDING));
        Mockito.when(reviewMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(1);
        UserContext.setRole(UserRole.ADMIN);

        service.approve(9L, 1L, "ok");

        ArgumentCaptor<Wrapper<SqlReviewRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(reviewMapper).update(ArgumentMatchers.isNull(), captor.capture());

        // 不显式写的话这一列由数据库的 ON UPDATE 填，而补偿任务用 JVM 时钟比 ——
        // 容器 TZ 一变，卡死判定就整体偏掉
        assertTrue(((LambdaUpdateWrapper<SqlReviewRequest>) captor.getValue()).getSqlSet().contains("updated_at"),
                "审批也要显式写 updated_at");
    }

    // ------------------------------------------------------------------
    // 幂等键：命中还要看内容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("幂等键命中但 SQL 不同：必须拒绝，绝不能返回另一条工单")
    void tokenReusedForDifferentSqlMustBeRejected() {
        SqlReviewSubmitDTO dto = submitDto("delete from t_user where id = 2");
        dto.setRequestToken("tok-1");

        // 库里那条是另一条 SQL —— 典型的"前端把 token 写死/一个会话一个"
        SqlReviewRequest other = existing(77L, 1L, ReviewStatus.PENDING);

        Mockito.when(reviewMapper.insert(ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("uk_user_token"));
        Mockito.when(reviewMapper.selectOne(ArgumentMatchers.any())).thenReturn(other);

        BusinessException e = assertThrows(BusinessException.class, () -> service.submit(1L, dto));

        // 直接返回旧工单的话，界面显示"提交成功"，而这条新 SQL 从来没入库、
        // 也永远不会被执行 —— 和"审批人看到 A、执行 B"是同一类问题
        assertTrue(e.getMessage().contains("requestToken"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("幂等键命中且内容完全相同：才返回已有工单")
    void sameTokenAndSameSqlShouldReturnExisting() {
        SqlReviewSubmitDTO dto = submitDto("update t_user set nickname = 'x' where id = 1");
        dto.setRequestToken("tok-1");

        Mockito.when(reviewMapper.insert(ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("uk_user_token"));
        Mockito.when(reviewMapper.selectOne(ArgumentMatchers.any()))
                .thenReturn(existing(77L, 1L, ReviewStatus.PENDING));

        assertEquals(77L, service.submit(1L, dto).getId());
    }
}
