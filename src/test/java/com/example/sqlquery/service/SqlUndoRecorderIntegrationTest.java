package com.example.sqlquery.service;

import com.example.sqlquery.common.UndoStatus;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.vo.DbSourceVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「前镜像 + 反向 SQL」对着<b>真实 MySQL</b> 跑一遍（L6 的验收）。
 *
 * <h2>为什么必须是集成测试</h2>
 *
 * <p>单测（{@code SqlUndoRecorderTest}）只能证明<b>生成的语句长什么样</b>；
 * 而这个功能的全部意义是「<b>出事时能把它跑回去</b>」——
 * 那就必须真的跑一遍。
 *
 * <p>本用例最关键的断言在第 ① 条：<b>把生成的反向 SQL 执行掉，数据必须回到原值。</b>
 * 少了这一步，我们交付的就只是一段"看起来对"的字符串。
 *
 * <h2>另外三条验的是「留不下来时会不会出事」</h2>
 *
 * <p>超过上限、表没有主键 —— 这两种情况<b>语句仍然必须执行</b>
 * （工单已经批过了，不能因为"留存不下来"就拒绝执行），但要如实记一条 SKIPPED。
 * 用例同时断言了这两件事。
 */
@Slf4j
@Tag("integration")
@SpringBootTest
class SqlUndoRecorderIntegrationTest {

    /** 与本项目其它用例不共用的测试用户 id；data_source.user_id 没有外键约束。 */
    private static final Long TEST_USER_ID = 990_004L;

    private static final String PROBE_TABLE = "it_undo_probe";
    private static final String NO_PK_TABLE = "it_undo_nopk";

    @Autowired
    private SqlUndoRecorder recorder;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private DataSource dataSource;

    private DbSource dbSource;
    private final List<Long> createdSourceIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dbSource = createDataSource();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            statement.execute("CREATE TABLE " + PROBE_TABLE
                    + " (id INT PRIMARY KEY, v INT NOT NULL, note VARCHAR(50))");
            statement.execute("INSERT INTO " + PROBE_TABLE + " VALUES (1, 100, 'a'), (2, 200, 'b'), (3, 300, 'c')");

            statement.execute("DROP TABLE IF EXISTS " + NO_PK_TABLE);
            statement.execute("CREATE TABLE " + NO_PK_TABLE + " (v INT)");
            statement.execute("INSERT INTO " + NO_PK_TABLE + " VALUES (1), (2)");
        }
    }

    /**
     * 收尾清理。<b>每一步各自 try/catch</b> ——
     * 一步失败不该让后面的清理也停掉（这个教训来自档案 T-03：
     * 平铺的清理步骤曾经往 data_source 里漏过 4 行数据）。
     */
    @AfterEach
    void cleanUp() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + NO_PK_TABLE);
        } catch (Exception e) {
            log.warn("清理探针表失败，已跳过。cause={}", e.toString());
        }

        for (Long id : createdSourceIds) {
            try {
                dbSourceService.removeById(id);
            } catch (Exception e) {
                log.warn("清理数据源失败，需要人工删除。id={}, cause={}", id, e.toString());
            }
        }
        createdSourceIds.clear();
    }

    // ------------------------------------------------------------------
    // ① 主用例：反向 SQL 真的能把数据还原
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ UPDATE：留存前镜像 → 生成反向 SQL → **跑回去数据真的还原了**")
    void updateInverseSqlShouldReallyRestoreTheRow() throws Exception {
        assertEquals(100, readValue(1), "前置条件：改之前是 100");

        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "UPDATE " + PROBE_TABLE + " SET v = 999, note = 'changed' WHERE id = 1");

        // 改动生效了
        assertEquals(1, result.affectedRows());
        assertEquals(999, readValue(1));

        // 而且留下来了
        assertEquals(UndoStatus.CAPTURED, result.capture().status());
        assertEquals(1, result.capture().rowCount());
        assertNotNull(result.capture().beforeJson());
        assertTrue(result.capture().beforeJson().contains("100"), "前镜像里必须有原值："
                + result.capture().beforeJson());
        assertTrue(result.capture().afterJson().contains("999"), "后镜像里必须有新值："
                + result.capture().afterJson());

        String inverseSql = result.capture().inverseSql();
        assertTrue(inverseSql.contains("UPDATE"), "实际：" + inverseSql);
        assertTrue(inverseSql.contains("100"), "反向 SQL 必须带上原值：实际：" + inverseSql);

        // ⭐⭐ 这一条才是这个功能存在的意义
        execute(inverseSql);
        assertEquals(100, readValue(1), "反向 SQL 跑完之后，v 必须回到 100");
        assertEquals("a", readNote(1), "note 也必须一起还原");
    }

    @Test
    @DisplayName("⭐⭐ 值里带反斜杠：反向 SQL 必须**逐字节**还原（只转义单引号会把数据改错）")
    void backslashValueShouldBeRestoredExactly() throws Exception {
        // 前镜像里放进一个带反斜杠的值（用参数化写，避免测试代码自己的转义干扰）
        String original = "C:\\temp\\new";
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement ps = connection.prepareStatement(
                     "UPDATE " + PROBE_TABLE + " SET note = ? WHERE id = 3")) {
            ps.setString(1, original);
            ps.executeUpdate();
        }
        assertEquals(original, readNote(3), "前置条件：库里确实存着一个带反斜杠的值");

        // ⚠️ 必须让这条语句**真的改到 note 列** ——
        //    反向 SQL 只包含"前后有变化的列"，不改 note 的话那个反斜杠根本不会进反向语句，
        //    测试就验不到东西（第一版就是这么写错的）。
        //    这条语句本身不含反斜杠 —— 反斜杠只出现在**前镜像**里。
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "UPDATE " + PROBE_TABLE + " SET note = 'no-backslash-anymore' WHERE id = 3");

        assertEquals(UndoStatus.CAPTURED, result.capture().status());
        assertEquals("no-backslash-anymore", readNote(3), "先确认改动生效");

        // 生成的反向 SQL 里，那个反斜杠必须是转义过的（写成两个）
        String inverseSql = result.capture().inverseSql();
        assertTrue(inverseSql.contains("\\\\"),
                "反向 SQL 里的反斜杠必须转义成两个，实际：" + inverseSql);

        // ⭐⭐ 决定性断言：跑回去之后必须**逐字节等于原值**
        execute(inverseSql);
        assertEquals(original, readNote(3),
                "反向 SQL 必须把值原样还原；如果只转义单引号，这里会变成 C:<退格>temp<退格>new");
    }

    @Test
    @DisplayName("⭐ DELETE：反向语句是 INSERT，跑回去那一行真的回来了")
    void deleteInverseSqlShouldReallyInsertTheRowBack() throws Exception {
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "DELETE FROM " + PROBE_TABLE + " WHERE id = 2");

        assertEquals(1, result.affectedRows());
        assertEquals(0, countRows("id = 2"), "删掉了");
        assertEquals(UndoStatus.CAPTURED, result.capture().status());
        assertTrue(result.capture().inverseSql().contains("INSERT INTO"),
                "实际：" + result.capture().inverseSql());

        execute(result.capture().inverseSql());

        assertEquals(1, countRows("id = 2"), "那一行必须回来了");
        assertEquals(200, readValue(2), "而且值要对");
        assertEquals("b", readNote(2));
    }

    @Test
    @DisplayName("没有任何行被改动时：不留存，也不会生成一条空的反向语句")
    void noMatchingRowShouldNotProduceInverseSql() throws Exception {
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "UPDATE " + PROBE_TABLE + " SET v = 1 WHERE id = 99999");

        assertEquals(0, result.affectedRows());
        assertEquals(UndoStatus.CAPTURED, result.capture().status());
        assertEquals(0, result.capture().rowCount());
        assertTrue(result.capture().inverseSql().isEmpty(), "没有人被改动，就不该有反向语句");
        assertNotNull(result.capture().reason(), "但要说明「无需回滚」");
    }

    // ------------------------------------------------------------------
    // ② 留不下来的时候：语句仍然要执行，但要如实记 SKIPPED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 超过留存上限：记 SKIPPED，但**语句照样执行**（工单已经批过了）")
    void overCapShouldSkipButStillExecute() throws Exception {
        ReflectionTestUtils.setField(recorder, "maxRows", 2);

        // 无 WHERE = 三行全改，超过上限 2
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "UPDATE " + PROBE_TABLE + " SET v = 7");

        assertEquals(UndoStatus.SKIPPED, result.capture().status());
        assertTrue(result.capture().reason().contains("超过留存上限"),
                "原因要写清楚，实际：" + result.capture().reason());

        // ⭐ 关键：不能因为"留存不下来"就拒绝执行
        assertEquals(3, result.affectedRows());
        assertEquals(7, readValue(1));
        assertEquals(7, readValue(3));
    }

    @Test
    @DisplayName("目标表没有主键：记 SKIPPED（没有主键就没法定位行），语句照样执行")
    void tableWithoutPrimaryKeyShouldSkip() throws Exception {
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "UPDATE " + NO_PK_TABLE + " SET v = 9");

        assertEquals(UndoStatus.SKIPPED, result.capture().status());
        assertTrue(result.capture().reason().contains("主键"),
                "实际：" + result.capture().reason());
        assertEquals(2, result.affectedRows());
    }

    @Test
    @DisplayName("DDL 之类无法生成反向 SQL 的语句：SKIPPED，仍然执行")
    void ddlShouldSkipButStillExecute() throws Exception {
        SqlUndoRecorder.WriteResult result = recorder.executeWithUndo(dbSource,
                "CREATE TABLE it_undo_ddl_probe (id INT)");

        assertEquals(UndoStatus.SKIPPED, result.capture().status());
        assertTrue(result.capture().reason().contains("反向 SQL"),
                "实际：" + result.capture().reason());

        execute("DROP TABLE IF EXISTS it_undo_ddl_probe");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private DbSource createDataSource() {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_UNDO_SRC_" + System.nanoTime());
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
        return dbSourceService.getById(created.getId());
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int readValue(int id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT v FROM " + PROBE_TABLE + " WHERE id = " + id)) {
            assertTrue(rs.next(), "id=" + id + " 的行不存在");
            return rs.getInt(1);
        }
    }

    private String readNote(int id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT note FROM " + PROBE_TABLE + " WHERE id = " + id)) {
            assertTrue(rs.next(), "id=" + id + " 的行不存在");
            return rs.getString(1);
        }
    }

    private int countRows(String where) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + PROBE_TABLE + " WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
