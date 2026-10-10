package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.util.ConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影响面预估。
 *
 * <h2>本类守的核心是一条<b>措辞</b>，不是一段逻辑</h2>
 *
 * <p>EXPLAIN 给的是<b>预估扫描行数</b>，不是<b>影响行数</b>。
 * 一条 {@code UPDATE ... WHERE status = '待支付'} 可能扫描 10 万行、只改 3 行。
 *
 * <p>把扫描行数当成「会改多少行」展示给审批人，是在
 * <b>制造错误的决策依据</b> —— 那比不给数字更糟：审批人会因为
 * 「才 3 行」而放行一条实际上要全表扫描、把库拖垮的语句。
 *
 * <p>所以断言写死在文案上：必须出现「扫描」，必须说清「不是最终会改动的行数」，
 * 并且<b>不能</b>出现「影响 N 行」这种说法。
 */
class SqlImpactAnalyzerTest {

    private ConnectionManager connectionManager;
    private SqlImpactAnalyzer analyzer;

    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    /** 真实 MySQL 8 的 EXPLAIN FORMAT=JSON 输出形状（已实测）。 */
    private static final String MYSQL_JSON =
            "{\"query_block\":{\"select_id\":1,\"table\":{"
                    + "\"table_name\":\"t_user\",\"access_type\":\"ALL\","
                    + "\"rows_examined_per_scan\":500,\"rows_produced_per_join\":500}}}";

    /** 真实 PostgreSQL 的 EXPLAIN (FORMAT JSON) 输出形状：最外层是数组。 */
    private static final String PG_JSON =
            "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Relation Name\":\"t_user\","
                    + "\"Plan Rows\":42,\"Plan Width\":8}}]";

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = Mockito.mock(ConnectionManager.class);
        analyzer = new SqlImpactAnalyzer(connectionManager, new ObjectMapper());

        connection = Mockito.mock(Connection.class);
        statement = Mockito.mock(Statement.class);
        resultSet = Mockito.mock(ResultSet.class);

        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any())).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(ArgumentMatchers.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.next()).thenReturn(true);
    }

    private DbSource dbSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(2L);
        return dbSource;
    }

    private void stubExplainJson(String json) throws SQLException {
        Mockito.when(resultSet.getString(1)).thenReturn(json);
    }

    @Test
    @DisplayName("MySQL：从 EXPLAIN FORMAT=JSON 里取到预估扫描行数")
    void mysqlShouldReadRowsFromJsonExplain() throws Exception {
        stubExplainJson(MYSQL_JSON);

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        assertEquals(500L, result.estimatedRows());
        assertEquals(List.of("t_user"), result.tables());
    }

    @Test
    @DisplayName("MySQL：EXPLAIN 语句必须是 FORMAT=JSON 那种写法")
    void mysqlExplainSyntax() throws Exception {
        stubExplainJson(MYSQL_JSON);

        analyzer.analyze(dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(statement).executeQuery(captor.capture());
        assertEquals("EXPLAIN FORMAT=JSON update t_user set nickname='x' where id=1", captor.getValue());
    }

    @Test
    @DisplayName("⚠️ 文案必须说「扫描」，且必须说清它不是最终会改动的行数")
    void noteMustNotCallScanRowsAffectedRows() throws Exception {
        stubExplainJson(MYSQL_JSON);

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        String note = result.note();
        assertTrue(note.contains("预估扫描约 500 行"), "必须明确写成扫描行数，实际：" + note);
        assertTrue(note.contains("不是最终会改动的行数"),
                "必须解释清楚这个数字的性质，否则审批人会误读，实际：" + note);
        assertFalse(note.contains("影响 500 行"),
                "绝不能把扫描行数说成影响行数 —— 那是给审批人制造错误的决策依据，实际：" + note);
    }

    @Test
    @DisplayName("全表扫描要被单独标出来 —— 这个信号比行数本身更值得让审批人看到")
    void fullScanShouldBeFlagged() throws Exception {
        stubExplainJson(MYSQL_JSON);

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        // MYSQL_JSON 里 access_type = ALL
        assertTrue(result.note().contains("全表扫描"), "实际：" + result.note());
    }

    @Test
    @DisplayName("不是全表扫描时不要乱喊狼来了")
    void nonFullScanShouldNotBeFlagged() throws Exception {
        stubExplainJson("{\"query_block\":{\"table\":{"
                + "\"access_type\":\"range\",\"rows_examined_per_scan\":3}}}");

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        assertFalse(result.note().contains("全表扫描"), "实际：" + result.note());
        assertEquals(3L, result.estimatedRows());
    }

    @Test
    @DisplayName("PostgreSQL：字段名完全不同（Plan Rows / Node Type），必须按方言取")
    void postgresShouldUseItsOwnFieldNames() throws Exception {
        stubExplainJson(PG_JSON);

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.POSTGRESQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        // PG 的 EXPLAIN 输出最外层是数组，且字段名带空格 ——
        // 直接套 MySQL 的 rows_examined_per_scan 会取到 null，
        // 而且不会报错，只是"这次没有预估"，静默地少了一个信息
        assertEquals(42L, result.estimatedRows());
        assertTrue(result.note().contains("全表扫描"), "Seq Scan 就是全表扫描，实际：" + result.note());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(statement).executeQuery(captor.capture());
        assertTrue(captor.getValue().startsWith("EXPLAIN (FORMAT JSON) "),
                "PG 的写法是带括号的，实际：" + captor.getValue());
    }

    @Test
    @DisplayName("嵌套执行计划要递归找：有 join / 子查询时行数不在固定位置")
    void nestedPlanShouldBeWalked() throws Exception {
        stubExplainJson("{\"query_block\":{\"nested_loop\":["
                + "{\"table\":{\"access_type\":\"ref\",\"rows_examined_per_scan\":5}},"
                + "{\"table\":{\"access_type\":\"ALL\",\"rows_examined_per_scan\":9000}}]}}");

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user", "t_order"));

        // 取的是最大值而不是求和：审批人关心"最坏那张表要扫多少行"
        assertEquals(9000L, result.estimatedRows());
        assertTrue(result.note().contains("全表扫描"));
    }

    @Test
    @DisplayName("EXPLAIN 失败不阻断提交 —— 但必须让审批人知道这次没有预估")
    void explainFailureShouldDegradeGracefully() throws Exception {
        Mockito.when(statement.executeQuery(ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("Table 't_user' doesn't exist"));

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        assertNull(result.estimatedRows());
        assertTrue(result.note().startsWith("预估失败"), "实际：" + result.note());
        assertEquals(List.of("t_user"), result.tables(), "预估失败不影响表名 —— 它来自 AST，不依赖数据库");
    }

    @Test
    @DisplayName("连不上数据源也不能让提交失败")
    void connectionFailureShouldNotThrow() throws Exception {
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any()))
                .thenThrow(new SQLException("connection refused"));

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        assertNull(result.estimatedRows());
        assertTrue(result.note().startsWith("预估失败"));
    }

    @Test
    @DisplayName("EXPLAIN 本身也要有超时 —— 统计信息缺失的表上它也可能很慢")
    void explainMustHaveTimeout() throws Exception {
        stubExplainJson(MYSQL_JSON);

        analyzer.analyze(dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        Mockito.verify(statement).setQueryTimeout(10);
    }

    @Test
    @DisplayName("EXPLAIN 没有返回行时也不崩")
    void emptyExplainResultShouldDegrade() throws Exception {
        Mockito.when(resultSet.next()).thenReturn(false);

        SqlImpactAnalyzer.ImpactAnalysis result = analyzer.analyze(
                dbSource(), SqlDialect.MYSQL, "update t_user set nickname='x' where id=1",
                List.of("t_user"));

        assertNull(result.estimatedRows());
        assertTrue(result.note().contains("没有返回结果"), "实际：" + result.note());
    }
}
