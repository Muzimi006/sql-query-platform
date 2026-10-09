package com.example.sqlquery.util;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接串的拼接。
 *
 * <p>多方言改造之后这个类的职责变了：它不再"拼 MySQL 连接串"，
 * 而是"先解析方言、再交给方言去拼"。所以本类的重点也变成了
 * <b>两种方言各自拼出什么</b>，以及<b>认不出来的类型必须报错而不是猜</b>。
 */
class JdbcUrlUtilTest {

    /** MySQL 的公共参数部分。引用常量而不是写死数字，避免两处各写一个 5000。 */
    private static final String MYSQL_PARAMS =
            "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS;

    private DbSource dbSource(String type, String host, Integer port, String databaseName) {
        DbSource dbSource = new DbSource();
        dbSource.setType(type);
        dbSource.setHost(host);
        dbSource.setPort(port);
        dbSource.setDatabaseName(databaseName);
        return dbSource;
    }

    @Test
    void shouldBuildMysqlUrlWithDatabaseName() {
        assertEquals(
                "jdbc:mysql://localhost:3306/test" + MYSQL_PARAMS,
                JdbcUrlUtil.build(dbSource("MySQL", "localhost", 3306, "test"))
        );
    }

    @Test
    void shouldBuildMysqlUrlWithoutDatabaseName() {
        assertEquals(
                "jdbc:mysql://localhost:3306" + MYSQL_PARAMS,
                JdbcUrlUtil.build(dbSource("MySQL", "localhost", 3306, null))
        );
    }

    @Test
    @DisplayName("PostgreSQL 拼出的是 jdbc:postgresql://，参数与 MySQL 完全不同")
    void shouldBuildPostgresqlUrl() {
        String url = JdbcUrlUtil.build(dbSource("PostgreSQL", "localhost", 5432, "demo"));

        assertTrue(url.startsWith("jdbc:postgresql://localhost:5432/demo?"), "实际：" + url);
        assertTrue(url.contains("sslmode=prefer"), "实际：" + url);
        // MySQL 专有的参数一个都不该出现
        assertTrue(!url.contains("useSSL=false"), "PG 连接串里混进了 MySQL 参数：" + url);
        assertTrue(!url.contains("serverTimezone"), "PG 连接串里混进了 MySQL 参数：" + url);
        assertTrue(!url.contains("allowPublicKeyRetrieval"), "PG 连接串里混进了 MySQL 参数：" + url);
    }

    @Test
    @DisplayName("★ connectTimeout 的单位不一样：MySQL 是毫秒，PG 是秒")
    void connectTimeoutUnitDiffersBetweenDialects() {
        String mysql = JdbcUrlUtil.build(dbSource("MySQL", "192.0.2.1", 3306, "db"));
        String pg = JdbcUrlUtil.build(dbSource("PostgreSQL", "192.0.2.1", 5432, "db"));

        assertTrue(mysql.contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "MySQL 的 connectTimeout 要用毫秒：" + mysql);

        // 5000 毫秒 = 5 秒。如果直接把毫秒值塞给 PG，等于设了 5000 秒的超时 —— 也就是没设。
        long expectedSeconds = JdbcUrlUtil.CONNECT_TIMEOUT_MS / 1000;
        assertTrue(pg.contains("connectTimeout=" + expectedSeconds),
                "PG 的 connectTimeout 要用秒：" + pg);
        assertTrue(!pg.contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "PG 的 connectTimeout 被写成了毫秒值，等于不超时：" + pg);
    }

    /**
     * 回归：连接串必须始终带 {@code connectTimeout}。
     *
     * <p>不带的话，MySQL Connector/J 的默认值是 0（不超时），等待时间交给操作系统 ——
     * Windows 约 21 秒、Linux 约 130 秒。用户在「测试连接」里填一个不可达主机时，
     * 请求就会卡这么久并一直占着 Tomcat 线程。
     */
    @Test
    void shouldAlwaysCarryConnectTimeout() {
        assertTrue(JdbcUrlUtil.build(dbSource("MySQL", "192.0.2.1", 3306, "db"))
                        .contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "MySQL 带库名的连接串缺少 connectTimeout");
        assertTrue(JdbcUrlUtil.build(dbSource("MySQL", "192.0.2.1", 3306, null))
                        .contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "MySQL 不带库名的连接串缺少 connectTimeout");
        assertTrue(JdbcUrlUtil.build(dbSource("PostgreSQL", "192.0.2.1", 5432, null))
                        .contains("connectTimeout="),
                "PostgreSQL 的连接串缺少 connectTimeout");
    }

    @Test
    @DisplayName("类型大小写不敏感（库里既有 mysql 也有 MySQL，实测）")
    void typeShouldBeCaseInsensitive() {
        assertEquals(
                JdbcUrlUtil.build(dbSource("MySQL", "h", 3306, "d")),
                JdbcUrlUtil.build(dbSource("mysql", "h", 3306, "d")));
        assertEquals(
                JdbcUrlUtil.build(dbSource("PostgreSQL", "h", 5432, "d")),
                JdbcUrlUtil.build(dbSource("postgresql", "h", 5432, "d")));
    }

    @Test
    @DisplayName("★ 认不出来的类型必须报错，不能猜一个默认方言")
    void unknownTypeShouldFailInsteadOfGuessing() {
        // 猜错的后果是"用 MySQL 的连接串去连 PostgreSQL"，
        // 那种失败很难从报错信息里看出真正原因
        BusinessException e = assertThrows(BusinessException.class,
                () -> JdbcUrlUtil.build(dbSource("Oracle", "h", 1521, "d")));
        assertTrue(e.getMessage().contains("暂不支持"), "实际：" + e.getMessage());

        assertThrows(BusinessException.class,
                () -> JdbcUrlUtil.build(dbSource(null, "h", 3306, "d")));
        assertThrows(BusinessException.class,
                () -> JdbcUrlUtil.build(dbSource("  ", "h", 3306, "d")));
    }
}
