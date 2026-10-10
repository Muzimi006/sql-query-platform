package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.dto.Nl2SqlRequestDTO;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.llm.LlmClient;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自然语言 → SQL → 工单。
 *
 * <h2>本类守的核心是一条：<b>AI 没有任何特权</b></h2>
 *
 * <p>模型生成的 SQL 和用户手敲的 SQL 必须走完全同一条路径：
 * 同样四道防线、同样一个工单状态机。这里把这条性质钉死 ——
 * 一旦有人为了"提高 AI 成功率"给它加一条宽松路径，这些用例会立刻变红。
 *
 * <p>另外两条同样是真实存在的失败模式：
 * <ul>
 *   <li>模型<b>硬编一个没有 WHERE 的 UPDATE</b> —— 所以约定了
 *       {@code NEED_MORE_INFO} 哨兵，让它说"我不知道"，而不是猜；</li>
 *   <li>模型习惯性在末尾加分号，{@code ;;} 会被解析成<b>两条语句</b>而拒绝 ——
 *       用户什么都没做错，是系统没规整干净。</li>
 * </ul>
 */
class NaturalLanguageSqlServiceTest {

    private LlmClient llmClient;
    private DbSourceService dbSourceService;
    private SchemaIntrospector schemaIntrospector;
    private SqlPermissionService sqlPermissionService;
    private SqlReviewService sqlReviewService;
    private NaturalLanguageSqlService service;

    private static final Set<SqlType> REVIEWABLE =
            Set.of(SqlType.INSERT, SqlType.UPDATE, SqlType.DELETE);

    @BeforeEach
    void setUp() {
        llmClient = Mockito.mock(LlmClient.class);
        dbSourceService = Mockito.mock(DbSourceService.class);
        schemaIntrospector = Mockito.mock(SchemaIntrospector.class);
        sqlPermissionService = Mockito.mock(SqlPermissionService.class);
        sqlReviewService = Mockito.mock(SqlReviewService.class);

        service = new NaturalLanguageSqlService(llmClient, dbSourceService,
                schemaIntrospector, sqlPermissionService, sqlReviewService);

        Mockito.when(dbSourceService.getEnabledByIdAndUserId(1L, 2L)).thenReturn(dbSource());
        Mockito.when(schemaIntrospector.describe(ArgumentMatchers.any()))
                .thenReturn("表 t_user(id BIGINT, nickname VARCHAR)\n");
        Mockito.when(sqlPermissionService.getReviewableTypes()).thenReturn(REVIEWABLE);
        Mockito.when(sqlReviewService.submitGenerated(ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(new SqlReviewRequestVO());
    }

    private DbSource dbSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(2L);
        dbSource.setUserId(1L);
        dbSource.setType("MYSQL");
        return dbSource;
    }

    private Nl2SqlRequestDTO dto(String prompt) {
        Nl2SqlRequestDTO dto = new Nl2SqlRequestDTO();
        dto.setDataSourceId(2L);
        dto.setPrompt(prompt);
        return dto;
    }

    private void llmReturns(String sql) {
        Mockito.when(llmClient.generateSql(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(sql);
    }

    // ------------------------------------------------------------------
    // AI 没有特权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型生成的危险 SQL 连工单都进不去 —— 四道防线对 AI 一样生效")
    void dangerousSqlFromModelMustBeRejected() {
        llmReturns("update t_user set nickname = 'x' where id = 1 and sleep(1)");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("把昵称改一下")));

        assertTrue(e.getMessage().contains("安全校验"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("危险函数"), "实际：" + e.getMessage());

        // ⚠️ 这条断言才是重点：不通过校验的 SQL **不会被提交成工单**。
        //    如果哪天为了"提高 AI 成功率"给它开一条绕过校验的路径，这里会变红
        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("模型访问系统库照样拦得住")
    void modelTouchingSystemSchemaMustBeRejected() {
        llmReturns("update mysql.user set authentication_string = 'x' where user = 'root'");

        assertThrows(BusinessException.class, () -> service.generateTicket(1L, dto("改个密码")));
        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("正常路径：走的是和手写工单完全相同的提交入口，只是来源标成 AI")
    void happyPathMustUseTheSameSubmitEntryPoint() {
        llmReturns("update t_user set nickname = 'x' where id = 1");

        service.generateTicket(1L, dto("把 1 号用户的昵称改成 x"));

        ArgumentCaptor<SqlReviewSubmitDTO> captor = ArgumentCaptor.forClass(SqlReviewSubmitDTO.class);
        Mockito.verify(sqlReviewService).submitGenerated(ArgumentMatchers.eq(1L), captor.capture(),
                ArgumentMatchers.eq("把 1 号用户的昵称改成 x"));

        assertEquals("update t_user set nickname = 'x' where id = 1", captor.getValue().getSql());
        assertEquals(2L, captor.getValue().getDataSourceId());
        // 原话必须带过去：审批人要靠它判断"意图对不对"，审计也要靠它回答"这是谁的意思"
        assertEquals("把 1 号用户的昵称改成 x", captor.getValue().getGenerationPrompt());
    }

    @Test
    @DisplayName("绝不自动执行：这条链路上只有一个出口，就是落成待审工单")
    void mustNeverExecuteDirectly() {
        llmReturns("delete from t_user where id = 1");

        service.generateTicket(1L, dto("删掉 1 号用户"));

        // 这个类里根本没有执行 SQL 的能力（没有 ConnectionManager、没有 QueryService），
        // 唯一能做的就是提交工单。这条断言是把"结构上不可能"也表达出来
        Mockito.verify(sqlReviewService, Mockito.times(1))
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    // ------------------------------------------------------------------
    // 模型说"信息不够"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型用 NEED_MORE_INFO 拒绝生成时，把它的追问原样转达给用户")
    void needMoreInfoShouldBeRelayed() {
        llmReturns("NEED_MORE_INFO: 需要知道是哪一天的余票，以及哪个景区");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("把库存调一下")));

        assertTrue(e.getMessage().contains("信息不够"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("哪一天"), "必须把模型的具体追问带出来，实际：" + e.getMessage());
        assertFalse(e.getMessage().contains("NEED_MORE_INFO"), "哨兵是内部约定，不该泄漏给用户");

        // 绝不能把 NEED_MORE_INFO 这句话当成 SQL 提交上去
        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("哨兵只有原因没写全时也要给出可用的提示")
    void needMoreInfoWithoutReason() {
        llmReturns("NEED_MORE_INFO");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("改一下")));

        assertTrue(e.getMessage().contains("信息不够"));
    }

    // ------------------------------------------------------------------
    // 提示词与规整
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提示词必须带上方言、真实表结构和该方言的禁用函数")
    void systemPromptMustCarryDialectSchemaAndBannedFunctions() {
        llmReturns("update t_user set nickname = 'x' where id = 1");

        service.generateTicket(1L, dto("改昵称"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llmClient).generateSql(captor.capture(), ArgumentMatchers.anyString());
        String systemPrompt = captor.getValue();

        assertTrue(systemPrompt.contains(SqlDialect.MYSQL.displayName()),
                "必须告诉模型是哪个方言，否则它会写出另一种方言的语法");
        assertTrue(systemPrompt.contains("表 t_user(id BIGINT, nickname VARCHAR)"),
                "必须带上真实表结构，否则模型只能编表名");
        assertTrue(systemPrompt.contains("sleep"),
                "必须把该方言的禁用函数写进提示词 —— 但注意这只是效率优化，不是安全边界");
        assertTrue(systemPrompt.contains("必须带 WHERE"),
                "必须明确要求 UPDATE/DELETE 带条件，这是防「误改全表」的第一道提示");
        assertTrue(systemPrompt.contains("NEED_MORE_INFO"),
                "必须告诉模型信息不够时怎么表达，否则它会硬编一个条件");
    }

    @Test
    @DisplayName("PostgreSQL 数据源要用 PG 的禁用函数，不能抄 MySQL 的")
    void postgresPromptMustUsePostgresFunctionList() {
        DbSource pg = dbSource();
        pg.setType("POSTGRESQL");
        Mockito.when(dbSourceService.getEnabledByIdAndUserId(1L, 2L)).thenReturn(pg);
        llmReturns("update t_user set nickname = 'x' where id = 1");

        service.generateTicket(1L, dto("改昵称"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llmClient).generateSql(captor.capture(), ArgumentMatchers.anyString());

        assertTrue(captor.getValue().contains("pg_sleep"),
                "PG 的禁用函数名单和 MySQL 完全不是一套，实际提示词：" + captor.getValue());
        assertTrue(captor.getValue().contains("PostgreSQL"));
    }

    @Test
    @DisplayName("模型返回 markdown 代码块时，围栏由客户端负责剥掉（这里只验证提交的是纯 SQL）")
    void fencedSqlShouldBeSubmittedAsPlainSql() {
        // OpenAiCompatibleLlmClient.stripCodeFence 已经剥过围栏，
        // 到这里拿到的应当已经是纯 SQL；这里验证的是本类不会画蛇添足地再处理一遍
        llmReturns("update t_user set nickname = 'x' where id = 1");

        service.generateTicket(1L, dto("改昵称"));

        ArgumentCaptor<SqlReviewSubmitDTO> captor = ArgumentCaptor.forClass(SqlReviewSubmitDTO.class);
        Mockito.verify(sqlReviewService).submitGenerated(ArgumentMatchers.anyLong(), captor.capture(),
                ArgumentMatchers.anyString());
        assertEquals("update t_user set nickname = 'x' where id = 1", captor.getValue().getSql());
    }

    @Test
    @DisplayName("尾部分号必须去掉：;; 会被解析成两条语句而被拒绝，而用户什么错都没有")
    void trailingSemicolonsMustBeStripped() {
        assertEquals("update t_user set x = 1 where id = 1",
                NaturalLanguageSqlService.normalize("update t_user set x = 1 where id = 1;"));
        assertEquals("update t_user set x = 1 where id = 1",
                NaturalLanguageSqlService.normalize("  update t_user set x = 1 where id = 1 ;;  "));
        assertEquals("", NaturalLanguageSqlService.normalize(null));
    }

    @Test
    @DisplayName("提交前先把分号去掉，落库的那条 SQL 是干净的")
    void submittedSqlShouldHaveNoTrailingSemicolon() {
        llmReturns("update t_user set nickname = 'x' where id = 1;");

        service.generateTicket(1L, dto("改昵称"));

        ArgumentCaptor<SqlReviewSubmitDTO> captor = ArgumentCaptor.forClass(SqlReviewSubmitDTO.class);
        Mockito.verify(sqlReviewService).submitGenerated(ArgumentMatchers.anyLong(), captor.capture(),
                ArgumentMatchers.anyString());
        assertEquals("update t_user set nickname = 'x' where id = 1", captor.getValue().getSql());
    }

    // ------------------------------------------------------------------
    // 用户问的其实是查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("用户问的是查询、模型给了 SELECT：不是拒绝，而是告诉他查询不用审批")
    void selectResultShouldPointToTheQueryPage() {
        llmReturns("select count(*) from t_order where status = 'PAID'");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("上周卖了多少张票")));

        assertTrue(e.getMessage().contains("不需要审核"), "实际：" + e.getMessage());
        // 生成的 SQL 必须还给用户：他本来就想查，不能只说一句"不行"
        assertTrue(e.getMessage().contains("select count(*) from t_order"),
                "必须把生成的查询语句一起给他，实际：" + e.getMessage());

        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("模型生成 DDL：明确说工单不受理，而不是含糊地报错")
    void ddlResultShouldBeRejectedWithItsOwnMessage() {
        llmReturns("drop table t_user");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("把这张表删了")));

        assertTrue(e.getMessage().contains("DDL") || e.getMessage().contains("工单受理范围"),
                "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("模型不可用时要能穿透到用户，且不产生任何工单")
    void llmUnavailableShouldPropagate() {
        Mockito.when(llmClient.generateSql(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException("自然语言生成 SQL 功能未启用"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.generateTicket(1L, dto("改昵称")));

        assertTrue(e.getMessage().contains("未启用"), "实际：" + e.getMessage());
        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("可用性由客户端决定，服务层不自己猜")
    void availabilityComesFromTheClient() {
        Mockito.when(llmClient.available()).thenReturn(false);
        assertFalse(service.available());

        Mockito.when(llmClient.available()).thenReturn(true);
        assertTrue(service.available());
    }

    /** 空 SQL 也应当被四道防线拦住，而不是被当成"没生成"放过去。 */
    @Test
    @DisplayName("模型返回空内容时被校验拦下")
    void emptyResultMustBeRejected() {
        Mockito.when(llmClient.generateSql(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer((InvocationOnMock inv) -> "   ");

        assertThrows(BusinessException.class, () -> service.generateTicket(1L, dto("改昵称")));
        Mockito.verify(sqlReviewService, Mockito.never())
                .submitGenerated(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }
}
