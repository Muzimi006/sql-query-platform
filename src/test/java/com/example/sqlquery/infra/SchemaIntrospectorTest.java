package com.example.sqlquery.infra;

import com.example.sqlquery.sql.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表结构读取。
 *
 * <h2>它守的是「发给大模型的提示词会不会失控」</h2>
 *
 * <p>这段文本是要花 token 的，而且是要<b>发到外部</b>的。
 * 所以三个上限（表数、列数、总字符数）必须有，
 * 而且截断之后必须<b>说出来</b> ——
 * 模型看不到某张表时不会说「我不知道」，它会<b>编一个看起来很像的表名</b>出来。
 * 悄悄截断 = 悄悄制造幻觉。
 */
class SchemaIntrospectorTest {

    private ConnectionManager connectionManager;
    private Connection connection;
    private DatabaseMetaData metaData;
    private SchemaIntrospector introspector;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = Mockito.mock(ConnectionManager.class);
        connection = Mockito.mock(Connection.class);
        metaData = Mockito.mock(DatabaseMetaData.class);

        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any())).thenReturn(connection);
        Mockito.when(connection.getMetaData()).thenReturn(metaData);

        introspector = new SchemaIntrospector(connectionManager);
    }

    private DbSource dbSource() {
        DbSource dbSource = new DbSource();
        dbSource.setId(2L);
        dbSource.setType("MYSQL");
        return dbSource;
    }

    /** 用一个内存行集合冒充 JDBC 的 ResultSet。 */
    private ResultSet rs(List<Map<String, String>> rows) throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);

        Mockito.when(rs.next()).thenAnswer(inv -> cursor.incrementAndGet() < rows.size());
        Mockito.when(rs.getString(ArgumentMatchers.anyString())).thenAnswer((InvocationOnMock inv) -> {
            int index = cursor.get();
            if (index < 0 || index >= rows.size()) {
                return null;
            }
            return rows.get(index).get(inv.getArgument(0));
        });
        return rs;
    }

    private Map<String, String> table(String schema, String name) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("TABLE_SCHEM", schema);
        row.put("TABLE_NAME", name);
        return row;
    }

    private Map<String, String> column(String name, String type) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("COLUMN_NAME", name);
        row.put("TYPE_NAME", type);
        return row;
    }

    private void stubMetaData(List<Map<String, String>> tables, List<Map<String, String>> columns)
            throws SQLException {
        // ⚠️ 必须先建好 ResultSet 再开桩。写成 .thenReturn(rs(tables)) 的话，
        //    rs() 内部又要调用 Mockito.when(...) —— 而外层那次桩还没结束，
        //    Mockito 会直接抛 UnfinishedStubbing。
        ResultSet tablesResultSet = rs(tables);
        Mockito.when(metaData.getTables(ArgumentMatchers.nullable(String.class),
                        ArgumentMatchers.nullable(String.class), ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(String[].class)))
                .thenReturn(tablesResultSet);
        // 每张表都要一个**新的** ResultSet：真实的 JDBC 也是每次调用返回新的游标
        Mockito.when(metaData.getColumns(ArgumentMatchers.nullable(String.class),
                        ArgumentMatchers.nullable(String.class), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenAnswer(inv -> rs(columns));
    }

    @Test
    @DisplayName("拼出「表名(列 类型, ...)」的形式")
    void shouldDescribeTablesAndColumns() throws Exception {
        stubMetaData(List.of(table("sql_query_platform", "t_user")),
                List.of(column("id", "BIGINT"), column("nickname", "VARCHAR")));

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        assertTrue(text.contains("表 t_user(id BIGINT, nickname VARCHAR)"), "实际：" + text);
    }

    @Test
    @DisplayName("系统库必须被过滤掉 —— 把 mysql.user 发给大模型是双重错误")
    void systemSchemasMustBeFilteredOut() throws Exception {
        stubMetaData(List.of(
                        table("mysql", "user"),
                        table("information_schema", "tables"),
                        table("sql_query_platform", "t_user")),
                List.of(column("id", "BIGINT")));

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        assertFalse(text.contains("mysql.user"), "系统表不能被当成业务表发出去，实际：" + text);
        assertFalse(text.contains("information_schema"), "实际：" + text);
        assertTrue(text.contains("t_user"), "实际：" + text);
    }

    @Test
    @DisplayName("PostgreSQL 过滤的是 PG 的系统库，名单来自方言而不是写死")
    void postgresSystemSchemasDiffer() throws Exception {
        stubMetaData(List.of(
                        table("pg_catalog", "pg_shadow"),
                        table("public", "t_order")),
                List.of(column("id", "int8")));

        String text = introspector.buildSchemaText(connection, SqlDialect.POSTGRESQL);

        assertFalse(text.contains("pg_shadow"), "实际：" + text);
        assertTrue(text.contains("t_order"), "实际：" + text);
    }

    @Test
    @DisplayName("表数超上限：截断并且**明说**已截断")
    void tableLimitMustBeReported() throws Exception {
        List<Map<String, String>> tables = new ArrayList<>();
        for (int i = 0; i < SchemaIntrospector.MAX_TABLES + 5; i++) {
            tables.add(table("sql_query_platform", "t_" + i));
        }
        stubMetaData(tables, List.of(column("id", "BIGINT")));

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        // 静默截断的后果是模型编表名，而用户完全不知道"是系统没把表给它看"
        assertTrue(text.contains("已截断"), "截断必须说明，实际：" + text);
        assertTrue(text.contains("t_" + (SchemaIntrospector.MAX_TABLES - 1)), "上限内的表要在");
        assertFalse(text.contains("t_" + (SchemaIntrospector.MAX_TABLES + 1)), "超出上限的表不该出现");
    }

    @Test
    @DisplayName("总字符数超上限也要截断并说明")
    void charLimitMustBeReported() throws Exception {
        List<Map<String, String>> tables = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tables.add(table("sql_query_platform", "very_long_table_name_number_" + i));
        }
        List<Map<String, String>> columns = new ArrayList<>();
        for (int i = 0; i < SchemaIntrospector.MAX_COLUMNS_PER_TABLE; i++) {
            columns.add(column("a_rather_long_column_name_" + i, "VARCHAR"));
        }
        stubMetaData(tables, columns);

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        assertTrue(text.length() <= SchemaIntrospector.MAX_CHARS + 200,
                "总长度必须有上限（提示词是要花钱的），实际：" + text.length());
        assertTrue(text.contains("已截断"), "实际：" + text);
    }

    @Test
    @DisplayName("一张用户表都没有时说清楚，而不是返回空字符串")
    void emptySchemaMustSaySo() throws Exception {
        stubMetaData(List.of(), List.of());

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        assertTrue(text.contains("没有读到任何用户表"), "实际：" + text);
    }

    @Test
    @DisplayName("每张表的列数也有上限，超了用省略号表示")
    void columnLimitPerTable() throws Exception {
        List<Map<String, String>> columns = new ArrayList<>();
        for (int i = 0; i < SchemaIntrospector.MAX_COLUMNS_PER_TABLE + 10; i++) {
            columns.add(column("c" + i, "INT"));
        }
        stubMetaData(List.of(table("sql_query_platform", "t_wide")), columns);

        String text = introspector.buildSchemaText(connection, SqlDialect.MYSQL);

        assertTrue(text.contains("…"), "列被截断时要有省略号，实际：" + text);
        assertFalse(text.contains("c" + (SchemaIntrospector.MAX_COLUMNS_PER_TABLE + 5)), "实际：" + text);
    }

    @Test
    @DisplayName("读表结构失败不能让整个请求失败，但要如实说明这次没有表结构")
    void failuresDegradeInsteadOfThrowing() throws Exception {
        Mockito.when(connectionManager.getConnection(ArgumentMatchers.any()))
                .thenThrow(new SQLException("connection refused"));

        String text = introspector.describe(dbSource());

        assertTrue(text.contains("读取表结构失败"), "实际：" + text);
        assertTrue(text.contains("connection refused"), "原因要带上，方便排障，实际：" + text);
    }

    @Test
    @DisplayName("有 TTL 缓存：连续的两次提问只连一次数据库")
    void shouldCacheWithinTtl() throws Exception {
        stubMetaData(List.of(table("sql_query_platform", "t_user")),
                List.of(column("id", "BIGINT")));

        String first = introspector.describe(dbSource());
        String second = introspector.describe(dbSource());

        assertEquals(first, second);
        // 元数据读取在某些驱动上并不便宜，不能每次提问都来一遍
        Mockito.verify(connectionManager, Mockito.times(1)).getConnection(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("evict 之后会重新读 —— 数据源连接信息被改过时必须能失效")
    void evictShouldForceReload() throws Exception {
        stubMetaData(List.of(table("sql_query_platform", "t_user")),
                List.of(column("id", "BIGINT")));

        introspector.describe(dbSource());
        introspector.evict(2L);
        introspector.describe(dbSource());

        Mockito.verify(connectionManager, Mockito.times(2)).getConnection(ArgumentMatchers.any());
    }
}
