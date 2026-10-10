package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.sqlquery.common.DbSourceStatus;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.common.UndoStatus;
import com.example.sqlquery.entity.SqlReviewUndo;
import com.example.sqlquery.mapper.SqlReviewUndoMapper;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.config.RabbitConfig;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.SqlReviewService;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核工单的<b>端到端</b>链路：提交 → 审批 → MQ → 执行 → 留痕。
 *
 * <h2>为什么要用真实 MySQL + 真实 RabbitMQ，而不是 mock</h2>
 *
 * <p>这条链路上要验的东西<b>恰好都在 mock 会抹掉的地方</b>：
 * <ul>
 *   <li>条件更新（{@code WHERE status = 'PENDING'}）在真实数据库上的行为 ——
 *       affectedRows 到底是 1 还是 0；</li>
 *   <li>MQ 的"至少一次"投递在真实 Broker 上是什么样子；</li>
 *   <li>写语句到底有没有真的落到数据上。</li>
 * </ul>
 *
 * <p>把数据库和 MQ 都 mock 掉，就等于把要验证的东西一起 mock 掉了。
 *
 * <h2>最关键的一条：<b>重复投递不能把写语句跑第二遍</b></h2>
 *
 * <p>所以探针表用的不是 {@code SET v = 42}（跑两次也是 42，看不出来），
 * 而是 <b>{@code SET v = v + 1}</b> —— 它<b>不幂等</b>，
 * 一旦被执行两次，值就会变成 2。这条用例的价值全在这个设计上。
 */
@Slf4j
@Tag("integration")
@SpringBootTest
class SqlReviewWorkflowIntegrationTest {

    /** 与被测数据源无关的固定测试用户 id；data_source.user_id 没有外键约束。 */
    private static final Long TEST_USER_ID = 990_002L;

    /** 审批人 id。只需要"不是申请人"，不要求这个用户在表里真的存在。 */
    private static final Long REVIEWER_ID = 990_003L;

    /** 探针表只在本用例内部创建/删除。 */
    private static final String PROBE_TABLE = "it_review_probe";

    @Autowired
    private SqlReviewService sqlReviewService;

    @Autowired
    private SqlReviewRequestMapper reviewMapper;

    @Autowired
    private SqlReviewUndoMapper undoMapper;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<Long> createdSourceIds = new ArrayList<>();

    /**
     * 收尾清理。<b>每一步都必须独立</b> —— 这是踩过坑之后改的。
     *
     * <h3>⚠️ 原来的写法为什么是错的</h3>
     *
     * <p>原来这四步是平铺的：先删工单、再删数据源、再删探针表。
     * 看起来没问题 —— 直到某一次 <b>{@code sql_review_request} 表还没建</b>
     * （迁移脚本没跑，见 {@code sql/migrate_003_sql_review.sql}）。
     *
     * <p>那时第一步就抛 {@code BadSqlGrammarException}，
     * <b>后面删数据源的循环一次都没执行</b> —— 于是每跑一次测试，
     * 就往 {@code data_source} 里留一行 {@code IT_REVIEW_SRC_xxx}。
     * 实测在库里留下了 4 行（id 202~205），而且它们**没有外键约束**，
     * 不会随任何东西级联删除，会一直待在那儿。
     *
     * <p>更麻烦的是它<b>很难被发现</b>：清理失败发生在 {@code @AfterEach} 里，
     * 而那时候测试本身往往已经是红的了 —— <b>一堆红色里多一条少一条，没人会去看</b>。
     *
     * <p>所以现在每一步各自 try/catch：<b>清理是一组互相独立的最佳努力，不是一条流水线。</b>
     * 一步失败不应该让后面的清理也停掉。
     */
    @AfterEach
    void cleanUp() {
        // ① 删工单（注意：删不掉也要继续往下清）
        try {
            reviewMapper.delete(new LambdaQueryWrapper<SqlReviewRequest>()
                    .eq(SqlReviewRequest::getUserId, TEST_USER_ID));
        } catch (Exception e) {
            log.warn("清理工单失败，已跳过（后续清理继续）。cause={}", e.toString());
        }

        // ② 删本用例建的数据源 —— 这一步最不能漏：漏掉就是永久留一行
        for (Long id : createdSourceIds) {
            try {
                dbSourceService.removeById(id);
            } catch (Exception e) {
                log.warn("清理数据源失败，需要人工删除。id={}, cause={}", id, e.toString());
            }
        }
        createdSourceIds.clear();

        // ③ 删探针表
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        } catch (Exception e) {
            log.warn("清理探针表失败，已跳过。cause={}", e.toString());
        }

        UserContext.clear();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void prepareProbeTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            statement.execute("CREATE TABLE " + PROBE_TABLE + " (id INT PRIMARY KEY, v INT)");
            statement.execute("INSERT INTO " + PROBE_TABLE + " VALUES (1, 0)");
        }
    }

    private int readProbeValue() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT v FROM " + PROBE_TABLE + " WHERE id = 1")) {
            assertTrue(rs.next(), "探针表的行不见了");
            return rs.getInt(1);
        }
    }

    private Long createDataSource() {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_REVIEW_SRC_" + System.nanoTime());
        dto.setType("MySQL");
        dto.setHost("127.0.0.1");
        dto.setPort(3306);
        dto.setDatabaseName("sql_query_platform");
        dto.setUsername(System.getenv().getOrDefault("DB_USERNAME", "root"));
        dto.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "123456"));
        dbSourceService.addDbSource(TEST_USER_ID, dto);

        DbSourceVO created = dbSourceService.listByUserId(TEST_USER_ID).stream()
                .filter(s -> s.getName().equals(dto.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚创建的数据源查不到"));
        createdSourceIds.add(created.getId());
        return created.getId();
    }

    private SqlReviewSubmitDTO ticket(String sql) {
        SqlReviewSubmitDTO dto = new SqlReviewSubmitDTO();
        dto.setDataSourceId(null);
        dto.setSql(sql);
        return dto;
    }

    private SqlReviewRequestVO submit(Long dataSourceId, String sql) {
        SqlReviewSubmitDTO dto = ticket(sql);
        dto.setDataSourceId(dataSourceId);
        return sqlReviewService.submit(TEST_USER_ID, dto);
    }

    /** 等工单进入某个状态。异步链路必须靠轮询，不能假设它已经跑完。 */
    private SqlReviewRequest awaitStatus(Long id, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        SqlReviewRequest last = null;
        while (System.currentTimeMillis() < deadline) {
            last = reviewMapper.selectById(id);
            if (last != null && expected.equals(last.getStatus())) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待工单进入 " + expected + " 超时，当前状态："
                + (last == null ? "查不到这条工单" : last.getStatus() + " / " + last.getErrorMessage()));
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整链路：提交(USER) → 审批(ADMIN) → 执行 → 留痕，且重复投递不会执行第二遍")
    void fullWorkflowShouldExecuteExactlyOnce() throws Exception {
        prepareProbeTable();
        Long dataSourceId = createDataSource();

        UserContext.set(TEST_USER_ID);
        UserContext.setRole("USER");

        SqlReviewRequestVO submitted = submit(dataSourceId,
                "UPDATE " + PROBE_TABLE + " SET v = v + 1 WHERE id = 1");

        // ---- 提交之后：只有一张待审工单，数据一个字节都没动 ----
        assertEquals(ReviewStatus.PENDING, submitted.getStatus());
        assertEquals("UPDATE", submitted.getSqlType());
        assertEquals(List.of(PROBE_TABLE), submitted.getInvolvedTables(),
                "AST 解析出的表名要展示给审批人");
        assertNotNull(submitted.getEstimateNote(), "影响面说明必须落库");
        assertEquals(0, readProbeValue(), "提交本身绝不能碰数据");

        // ---- USER 自己批不了自己的工单 ----
        UserContext.setRole("USER");
        assertThrows(BusinessException.class,
                () -> sqlReviewService.approve(TEST_USER_ID, submitted.getId(), null));

        // ---- 管理员批准 ----
        UserContext.setRole("ADMIN");
        sqlReviewService.approve(REVIEWER_ID, submitted.getId(), "看过了，可以");

        SqlReviewRequest executed = awaitStatus(submitted.getId(), ReviewStatus.EXECUTED, 30_000);

        // ---- 留痕：谁批的、什么时候批的、什么时候执行的、改了多少行 ----
        assertEquals(REVIEWER_ID, executed.getReviewerId());
        assertEquals("看过了，可以", executed.getReviewComment());
        assertNotNull(executed.getReviewedAt(), "审批时间必须留痕");
        assertNotNull(executed.getExecutedAt(), "执行时间必须留痕");
        assertEquals(1L, executed.getAffectedRows(), "影响行数必须回写到工单上");
        assertEquals(1, readProbeValue(), "写语句必须真的生效");

        // ---- ⭐ 前镜像留存：这是「批错了怎么办」的唯一依据 ----
        // 这条断言走的是**完整的消费者路径**（MQ → 认领 → 取像 → 执行 → 落库），
        // 而 SqlUndoRecorderTest 那 15 条单测只验了生成逻辑。两者都要有：
        // 单测证明「语句长什么样」，这里证明「接线真的通了」。
        SqlReviewUndo undo = undoMapper.selectById(submitted.getId());
        assertNotNull(undo, "执行成功后必须有前镜像留存记录");
        assertEquals(UndoStatus.CAPTURED, undo.getCaptureStatus());
        assertEquals(PROBE_TABLE, undo.getTableName());
        assertEquals(1, undo.getRowCount(), "只改了一行，就只该留一行的前镜像");
        assertNotNull(undo.getBeforeJson(), "前镜像不能为空");
        assertNotNull(undo.getInverseSql(), "必须生成反向 SQL（那就是回滚依据）");
        assertTrue(undo.getInverseSql().contains("UPDATE"),
                "这条语句是 UPDATE，反向语句也应该是 UPDATE。实际：" + undo.getInverseSql());

        // ---- 重复投递：MQ 是「至少一次」，同一条消息出现两次是正常情况 ----
        UserContext.clear();
        rabbitTemplate.convertAndSend(RabbitConfig.REVIEW_QUEUE, submitted.getId(),
                new CorrelationData("duplicate-" + submitted.getId()));
        Thread.sleep(3000);

        // ⚠️ 这条断言是本用例的核心。
        //    UPDATE ... SET v = v + 1 是不幂等的：真的跑了第二遍，值会变成 2。
        //    挡住它的是消费者那个「只有当前状态是 APPROVED 才能认领」的条件更新。
        assertEquals(1, readProbeValue(), "重复投递绝不能把写语句再跑一遍");
        assertEquals(ReviewStatus.EXECUTED, reviewMapper.selectById(submitted.getId()).getStatus(),
                "重复投递也不该把已完成的工单改坏");
    }

    @Test
    @DisplayName("被驳回的工单：一步都不会执行，且驳回原因是必填的")
    void rejectedTicketMustNeverExecute() throws Exception {
        prepareProbeTable();
        Long dataSourceId = createDataSource();

        UserContext.set(TEST_USER_ID);
        SqlReviewRequestVO submitted = submit(dataSourceId,
                "UPDATE " + PROBE_TABLE + " SET v = 99 WHERE id = 1");

        UserContext.setRole("ADMIN");

        // 驳回不写原因时必须是明确的错误：申请人要靠它知道改什么
        assertThrows(BusinessException.class,
                () -> sqlReviewService.reject(REVIEWER_ID, submitted.getId(), "   "));

        sqlReviewService.reject(REVIEWER_ID, submitted.getId(), "where 条件太宽，会改到全表");

        SqlReviewRequest rejected = awaitStatus(submitted.getId(), ReviewStatus.REJECTED, 5_000);
        assertEquals("where 条件太宽，会改到全表", rejected.getReviewComment());
        assertNull(rejected.getExecutedAt(), "被驳回的工单不该有执行时间");

        // 给消费者足够的时间去"万一执行了" —— 它不该执行
        Thread.sleep(2000);
        assertEquals(0, readProbeValue(), "被驳回的工单绝不能被执行");
    }

    @Test
    @DisplayName("审批期间数据源被停用：执行阶段必须失败，而不是照跑不误")
    void disabledDataSourceMustNotExecute() throws Exception {
        prepareProbeTable();
        Long dataSourceId = createDataSource();

        UserContext.set(TEST_USER_ID);
        SqlReviewRequestVO submitted = submit(dataSourceId,
                "UPDATE " + PROBE_TABLE + " SET v = 5 WHERE id = 1");

        // 模拟"工单排了很久队，轮到执行时数据源已经被停用"
        dbSourceService.changeStatus(TEST_USER_ID, dataSourceId, DbSourceStatus.DISABLED);

        UserContext.setRole("ADMIN");
        sqlReviewService.approve(REVIEWER_ID, submitted.getId(), null);

        SqlReviewRequest failed = awaitStatus(submitted.getId(), ReviewStatus.FAILED, 30_000);

        assertNotNull(failed.getErrorMessage(), "失败必须写明原因");
        assertTrue(failed.getErrorMessage().contains("停用") || failed.getErrorMessage().contains("不存在"),
                "实际：" + failed.getErrorMessage());
        assertEquals(0, readProbeValue(), "停用的数据源上不能执行任何写操作");
    }

    @Test
    @DisplayName("工单里存的 SQL 就是审批之后执行的那条 —— 不接受前端再传一次")
    void executedSqlComesFromTheTicketNotFromTheCaller() throws Exception {
        prepareProbeTable();
        Long dataSourceId = createDataSource();

        UserContext.set(TEST_USER_ID);
        SqlReviewRequestVO submitted = submit(dataSourceId,
                "UPDATE " + PROBE_TABLE + " SET v = 77 WHERE id = 1");

        UserContext.setRole("ADMIN");
        // 审批接口的 DTO 里根本没有 sql 字段 —— 这个约束在**结构上**被固定死了。
        // 这里再确认一次：执行完之后，数据的变化与工单里那条 SQL 完全一致
        sqlReviewService.approve(REVIEWER_ID, submitted.getId(), null);

        awaitStatus(submitted.getId(), ReviewStatus.EXECUTED, 30_000);
        assertEquals(77, readProbeValue(), "执行的就该是工单里存的那条 SQL");
    }
}
