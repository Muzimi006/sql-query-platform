package com.example.sqlquery.util;

import com.example.sqlquery.entity.DbSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcUrlUtilTest {

    /** 公共参数部分。引用常量而不是写死数字，避免两处各写一个 5000。 */
    private static final String COMMON_PARAMS =
            "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS;

    private DbSource dbSource(String host, Integer port, String databaseName) {
        DbSource dbSource = new DbSource();
        dbSource.setHost(host);
        dbSource.setPort(port);
        dbSource.setDatabaseName(databaseName);
        return dbSource;
    }

    @Test
    void shouldBuildUrlWithDatabaseName() {
        assertEquals(
                "jdbc:mysql://localhost:3306/test" + COMMON_PARAMS,
                JdbcUrlUtil.build(dbSource("localhost", 3306, "test"))
        );
    }

    @Test
    void shouldBuildUrlWithoutDatabaseName() {
        assertEquals(
                "jdbc:mysql://localhost:3306" + COMMON_PARAMS,
                JdbcUrlUtil.build(dbSource("localhost", 3306, null))
        );
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
        String withDatabase = JdbcUrlUtil.build(dbSource("192.0.2.1", 3306, "db"));
        String withoutDatabase = JdbcUrlUtil.build(dbSource("192.0.2.1", 3306, null));

        assertTrue(withDatabase.contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "带库名的连接串缺少 connectTimeout：" + withDatabase);
        assertTrue(withoutDatabase.contains("connectTimeout=" + JdbcUrlUtil.CONNECT_TIMEOUT_MS),
                "不带库名的连接串缺少 connectTimeout：" + withoutDatabase);
    }
}
