package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.vo.QueryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写语句（INSERT / UPDATE / DELETE）的执行链路。
 *
 * <p>守的失败模式：<b>权限层放行了，执行层却做不到</b>。
 *
 * <p>这个用例是被端到端测试<strong>逼出来</strong>的：给 ADMIN 角色配上写权限之后，
 * 用 ADMIN 执行一条 UPDATE，拿到的不是"执行成功、影响 N 行"，而是
 * {@code Statement.executeQuery() cannot issue statements that do not produce result sets}。
 *
 * <p>原因是 {@code doExecute} 一直用 {@code statement.executeQuery(sql)}，
 * 而它<b>只接受会产生结果集的语句</b>。这个限制在"所有角色都只能 SELECT"的时候
 * 完全看不出来 —— 那正是它藏了这么久的原因。
 *
 * <p>它用真实 MySQL，因为要验的恰恰是"数据库驱动对不同语句类型的反应"，
 * 用 mock 测等于把要验证的东西一起 mock 掉了。
 */
@Tag("integration")
@SpringBootTest
class QueryWriteStatementIntegrationTest {

    /** 与被测数据源无关的固定测试用户 id；data_source.user_id 没有外键约束。 */
    private static final Long TEST_USER_ID = 990_002L;

    /** 探针表只在本用例内部创建/删除，应用侧看不到它的存在。 */
    private static final String PROBE_TABLE = "it_write_probe";

    @Autowired
    private QueryService queryService;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private DataSource dataSource;

    private final List<Long> createdSourceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (Long id : createdSourceIds) {
            dbSourceService.removeById(id);
        }
        createdSourceIds.clear();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        }
        UserContext.clear();
    }

    /** 直接用 JDBC 建探针表 —— 不走应用，所以不受平台 SQL 白名单的限制。 */
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
        dto.setName("IT_WRITE_SRC_" + System.nanoTime());
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

    @Test
    @DisplayName("ADMIN 执行 UPDATE：必须真的改到数据，并返回影响行数")
    void adminShouldBeAbleToRunUpdateAndGetAffectedRows() throws Exception {
        prepareProbeTable();
        UserContext.setRole("ADMIN");   // 测试线程里没有 Web 请求，角色要自己设置

        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(createDataSource());
        dto.setSql("UPDATE " + PROBE_TABLE + " SET v = 7 WHERE id = 1");

        QueryVO result = queryService.execute(TEST_USER_ID, dto);

        assertEquals(1, result.getAffectedRows(), "影响行数应该透传出来");
        assertEquals(7, readProbeValue(), "数据必须真的被改了 —— 只是接口不报错不算数");
        // 写语句没有结果集：columns/rows 是空的，而不是 null
        assertNotNull(result.getColumns());
        assertNotNull(result.getRows());
        assertTrue(result.getColumns().isEmpty());
    }

    @Test
    @DisplayName("UPDATE 没匹配到行时 affectedRows 是 0，而不是 null")
    void updateMatchingNothingShouldReportZero() throws Exception {
        prepareProbeTable();
        UserContext.setRole("ADMIN");

        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(createDataSource());
        dto.setSql("UPDATE " + PROBE_TABLE + " SET v = 9 WHERE id = 999");

        QueryVO result = queryService.execute(TEST_USER_ID, dto);

        // "影响 0 行"和"这条语句不返回结果集"是两件事，前端要靠 affectedRows 区分
        assertEquals(0, result.getAffectedRows());
        assertEquals(0, readProbeValue(), "没有匹配到行时不该改动任何数据");
    }

    @Test
    @DisplayName("SELECT 的 affectedRows 是 null，走的是结果集那条分支")
    void selectShouldNotPopulateAffectedRows() throws Exception {
        prepareProbeTable();
        UserContext.setRole("ADMIN");

        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(createDataSource());
        dto.setSql("SELECT v FROM " + PROBE_TABLE);

        QueryVO result = queryService.execute(TEST_USER_ID, dto);

        assertNull(result.getAffectedRows(), "查询语句不该有影响行数");
        assertEquals(1, result.getRows().size());
        assertEquals(0, result.getRows().get(0).get("v"));
    }

    @Test
    @DisplayName("USER 执行 UPDATE 仍然必须被权限层拦下")
    void userShouldStillBeBlockedFromWriting() throws Exception {
        prepareProbeTable();
        UserContext.setRole("USER");

        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(createDataSource());
        dto.setSql("UPDATE " + PROBE_TABLE + " SET v = 7 WHERE id = 1");

        com.example.sqlquery.exception.BusinessException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.example.sqlquery.exception.BusinessException.class,
                        () -> queryService.execute(TEST_USER_ID, dto));

        assertEquals("没有权限执行该类型SQL", e.getMessage());
        assertEquals(0, readProbeValue(), "被拦下的语句绝不能真的执行");
    }
}
