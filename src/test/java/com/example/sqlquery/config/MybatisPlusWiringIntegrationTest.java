package com.example.sqlquery.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.vo.DbSourceVO;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus 装配完整性 + 两个静默 bug 的回归测试。
 *
 * <p>这条用例是<b>对着真实 MySQL 跑的</b>（打 integration 标签），因为要证明的
 * 恰恰是"SQL 到底有没有被改写成带 LIMIT 的形式""数据库列里到底有没有值" ——
 * 用 mock 测这两件事等于什么都没测。
 *
 * <p>它守住的失败模式：
 * <ul>
 *   <li>手工装配 SqlSessionFactory 时漏掉 {@code setPlugins} →
 *       分页静默失效：{@code records} 返回全表、{@code total} 恒为 0；</li>
 *   <li>漏掉 {@code setGlobalConfig(metaObjectHandler)} →
 *       {@code @TableField(fill = INSERT)} 的列被显式写入 NULL，
 *       连库表的 {@code DEFAULT CURRENT_TIMESTAMP} 都被顶掉。</li>
 * </ul>
 *
 * <p>运行方式见 README「测试」一节（需要本地 MySQL + 四个环境变量）。
 */
@Tag("integration")
@SpringBootTest(properties = {
        // 本用例只验证数据库层装配，不需要真的连上 RabbitMQ。
        // 关掉监听器自启动，避免上下文启动时反复重连产生噪音日志。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class MybatisPlusWiringIntegrationTest {

    /** 测试数据用固定前缀命名，收尾时按前缀清理，不会碰到手工造的数据。 */
    private static final String NAME_PREFIX = "IT_PAGING_";

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private DataSource dataSource;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 用例失败也要清理，否则残留数据会污染下一次运行（尤其是分页总数断言）
        for (Long id : createdIds) {
            dbSourceService.removeById(id);
        }
        createdIds.clear();
    }

    @Test
    @DisplayName("SqlSessionFactory 里必须同时装着分页拦截器和自动填充处理器")
    void sqlSessionFactoryMustHavePaginationAndMetaObjectHandler() {
        List<Interceptor> interceptors = sqlSessionFactory.getConfiguration().getInterceptors();
        assertFalse(interceptors.isEmpty(),
                "SqlSessionFactory 里一个插件都没有 —— setPlugins 没调，分页会静默失效");

        MybatisPlusInterceptor mybatisPlusInterceptor = interceptors.stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有注册 MybatisPlusInterceptor"));

        boolean hasPagination = mybatisPlusInterceptor.getInterceptors().stream()
                .map(InnerInterceptor::getClass)
                .anyMatch(PaginationInnerInterceptor.class::equals);
        assertTrue(hasPagination, "MybatisPlusInterceptor 里没有 PaginationInnerInterceptor");

        // 平台元数据库固定用 MySQL 方言（与用户业务数据源的 type 无关）
        PaginationInnerInterceptor pagination = mybatisPlusInterceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(DbType.MYSQL, pagination.getDbType());
    }

    @Test
    @DisplayName("分页必须真的生效：size=1 时只能返回 1 条，且 total 是总数")
    void paginationMustActuallyLimitRowsAndFillTotal() {
        Long userId = insertThreeSources();

        IPage<DbSourceVO> firstPage = dbSourceService.pageByUserId(userId, 1, 1);

        assertEquals(3, firstPage.getTotal(),
                "total 没回填 —— SQL 里没有拼 COUNT，说明分页拦截器没生效");
        assertEquals(1, firstPage.getRecords().size(),
                "size=1 却返回了多于 1 条 —— SQL 里没有拼 LIMIT，说明分页拦截器没生效");
    }

    @Test
    @DisplayName("插入时 created_at / updated_at 必须落库，不能被写成 NULL")
    void insertFillMustWriteTimestampsToDatabase() throws Exception {
        DbSourceDTO dto = new DTOBuilder("IT_PAGING_FILL_1").build();
        dbSourceService.addDbSource(1L, dto);

        Long id = dbSourceService.listByUserId(1L).stream()
                .filter(s -> "IT_PAGING_FILL_1".equals(s.getName()))
                .map(DbSourceVO::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚插入的数据源查不到"));
        createdIds.add(id);

        // 关键：直接读数据库列，而不是读实体。
        // 只断言实体的话，测的是"处理器有没有给对象赋值"，
        // 而真正要守的是"这一列在库里有没有值" —— 之前正是列里被写成了 NULL。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "select created_at, updated_at from data_source where id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "查不到刚插入的行");
                assertNotNull(rs.getTimestamp("created_at"),
                        "created_at 在库里是 NULL —— 自动填充没生效（MetaObjectHandler 没装进 SqlSessionFactory）");
                assertNotNull(rs.getTimestamp("updated_at"),
                        "updated_at 在库里是 NULL —— 自动填充没生效");
            }
        }
    }

    /** 造 3 条同一个用户的数据源，返回 userId。数据源必须属于同一个用户，分页断言才有意义。 */
    private Long insertThreeSources() {
        Long userId = System.currentTimeMillis() % 1_000_000L + 900_000_000L;
        for (int i = 1; i <= 3; i++) {
            DbSourceDTO dto = new DTOBuilder(NAME_PREFIX + i).build();
            dbSourceService.addDbSource(userId, dto);
        }
        for (DbSourceVO vo : dbSourceService.listByUserId(userId)) {
            createdIds.add(vo.getId());
        }
        return userId;
    }

    /** 测试用的数据源构造：字段值都是占位，本用例只关心分页与时间列，不真的去连它。 */
    private static final class DTOBuilder {
        private final DbSourceDTO dto = new DbSourceDTO();

        DTOBuilder(String name) {
            dto.setName(name);
            dto.setType("MySQL");
            dto.setHost("127.0.0.1");
            dto.setPort(3306);
            dto.setDatabaseName("sql_query_platform");
            dto.setUsername("query_user");
            dto.setPassword("QueryUser123456");
        }

        DbSourceDTO build() {
            return dto;
        }
    }
}
