package com.example.sqlquery.sql;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.ExportService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.vo.QueryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>真实 PostgreSQL</b> 下的双方言端到端验证。
 *
 * <h2>这个用例要回答的问题</h2>
 *
 * <p>改造前，整个项目到处写死了 MySQL：连接串是 {@code jdbc:mysql://}、
 * 危险函数名单里是 {@code sleep} / {@code load_file}。而前端下拉框里已经有一个别的数据库选项 ——
 * <b>选了它也照样拼出一条 MySQL 连接串，没有任何地方会报错</b>。
 *
 * <p>所以这里必须用<b>真的 PostgreSQL</b> 来验，不能 mock：
 * <ul>
 *   <li>连接串前缀、驱动类名、连接参数的单位（PG 的 connectTimeout 是<b>秒</b>）——
 *       任何一处错了都连不上；</li>
 *   <li>危险函数名单是不是真的换成了 PG 那一套 ——
 *       拿 MySQL 的名单去检查 PG 的 SQL，表现是"检查通过了但什么都没检查"，只有真跑才看得出来。</li>
 * </ul>
 *
 * <p>前置条件：`docker compose up -d postgres`，库里有 {@code demo_user} 表
 * （见 README「测试」一节）。
 */
@Tag("integration")
@SpringBootTest
class PostgresDialectIntegrationTest {

    private static final Long TEST_USER_ID = 990_003L;

    /** 与被测环境无关的固定连接信息，与 docker-compose.yml 里的 postgres 服务一致。 */
    private static final String PG_HOST = "127.0.0.1";
    private static final int PG_PORT = 5432;
    private static final String PG_DATABASE = "sql_query_platform_demo";
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "123456";

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ExportService exportService;

    private final List<Long> createdSourceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdSourceIds) {
            dbSourceService.removeById(id);
        }
        createdSourceIds.clear();
        UserContext.clear();
    }

    private DbSourceDTO postgresDto() {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_PG_SRC_" + System.nanoTime());
        dto.setType("PostgreSQL");
        dto.setHost(PG_HOST);
        dto.setPort(PG_PORT);
        dto.setDatabaseName(PG_DATABASE);
        dto.setUsername(PG_USER);
        dto.setPassword(PG_PASSWORD);
        return dto;
    }

    private Long createPostgresDataSource() {
        DbSourceDTO dto = postgresDto();
        dbSourceService.addDbSource(TEST_USER_ID, dto);

        DbSourceVO created = dbSourceService.listByUserId(TEST_USER_ID).stream()
                .filter(s -> s.getName().equals(dto.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚创建的数据源查不到"));
        createdSourceIds.add(created.getId());
        return created.getId();
    }

    private QueryDTO query(Long dataSourceId, String sql) {
        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(dataSourceId);
        dto.setSql(sql);
        return dto;
    }

    @Test
    @DisplayName("★ 能真的连上 PostgreSQL —— 连接串前缀、驱动、参数单位任何一处错了都过不了")
    void shouldConnectToRealPostgres() {
        assertTrue(dbSourceService.testConnection(postgresDto()),
                "连不上真实的 PostgreSQL。连接串前缀 / 驱动类名 / connectTimeout 单位（PG 是秒）都要检查");
    }

    @Test
    @DisplayName("通过平台执行 PG 查询，结果正确返回")
    void shouldExecuteQueryAgainstPostgres() {
        UserContext.setRole("USER");
        Long dataSourceId = createPostgresDataSource();

        QueryVO result = queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select id, name from demo_user order by id"));

        assertEquals(2, result.getRows().size());
        assertEquals("alice", result.getRows().get(0).get("name"));
        assertEquals("bob", result.getRows().get(1).get("name"));
        assertTrue(result.getColumns().contains("id"));
    }

    @Test
    @DisplayName("PG 专有语法要被平台正常接受")
    void postgresqlSyntaxShouldWork() {
        UserContext.setRole("USER");
        Long dataSourceId = createPostgresDataSource();

        // :: 类型转换、ILIKE、generate_series —— 这三样 MySQL 都没有
        QueryVO cast = queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select id::text as id_text from demo_user where id = 1"));
        assertEquals("1", cast.getRows().get(0).get("id_text"));

        QueryVO ilike = queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select id from demo_user where name ilike 'ALI%'"));
        assertEquals(1, ilike.getRows().size());

        QueryVO series = queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select * from generate_series(1, 3)"));
        assertEquals(3, series.getRows().size());
    }

    @Test
    @DisplayName("★ PG 的危险函数必须被 PG 自己的名单拦下")
    void postgresqlDangerousFunctionsMustBeBlocked() {
        UserContext.setRole("USER");
        Long dataSourceId = createPostgresDataSource();

        BusinessException e = assertThrows(BusinessException.class,
                () -> queryService.execute(TEST_USER_ID, query(dataSourceId, "select pg_sleep(1)")));
        assertTrue(e.getMessage().contains("危险函数"),
                "PG 的危险函数没被拦下 —— 很可能是名单还是 MySQL 那一套。实际：" + e.getMessage());

        assertThrows(BusinessException.class, () -> queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select pg_read_file('/etc/passwd')")));
    }

    @Test
    @DisplayName("★ PG 的 pg_catalog 不带前缀也能访问，必须靠裸表名检查拦住")
    void postgresqlBareCatalogTableMustBeBlocked() {
        UserContext.setRole("USER");
        Long dataSourceId = createPostgresDataSource();

        // pg_shadow 里是口令哈希。PG 的 pg_catalog 默认在 search_path 里，
        // 所以这条 SQL 不带任何库名前缀 —— 只查前缀的实现会整个漏掉
        BusinessException e = assertThrows(BusinessException.class,
                () -> queryService.execute(TEST_USER_ID, query(dataSourceId, "select * from pg_shadow")));
        assertTrue(e.getMessage().contains("系统表"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("PG 上无害的系统视图与普通函数不能被误拦")
    void harmlessPostgresObjectsShouldPass() {
        UserContext.setRole("USER");
        Long dataSourceId = createPostgresDataSource();

        assertEquals(1, queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select 1 as n")).getRows().size());
        assertEquals(1, queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select now() as ts")).getRows().size());
        // pg_type 是 pg_catalog 里的无害表，不该被"禁止整个 pg_catalog"式的实现误杀
        queryService.execute(TEST_USER_ID,
                query(dataSourceId, "select typname from pg_type limit 1"));
    }

    @Test
    @DisplayName("★ 导出链路也按数据源的方言检查（不是只看查询链路）")
    void exportPathMustAlsoUseTheDataSourceDialect() {
        UserContext.setRole("ADMIN");   // 用 ADMIN 排除"权限不足"造成的干扰
        Long dataSourceId = createPostgresDataSource();

        ExportTaskDTO dto = new ExportTaskDTO();
        dto.setDataSourceId(dataSourceId);
        dto.setSql("select pg_sleep(1)");

        BusinessException e = assertThrows(BusinessException.class,
                () -> exportService.createTask(TEST_USER_ID, dto));
        assertTrue(e.getMessage().contains("危险函数"),
                "导出链路没有按 PG 的方言检查。实际：" + e.getMessage());
    }
}
