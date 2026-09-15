package com.example.sqlquery.util;

import com.example.sqlquery.entity.DbSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcUrlUtilTest {

    @Test
    void shouldBuildUrlWithDatabaseName() {
        DbSource dbSource = new DbSource();
        dbSource.setHost("localhost");
        dbSource.setPort(3306);
        dbSource.setDatabaseName("test");

        assertEquals(
                "jdbc:mysql://localhost:3306/test?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                JdbcUrlUtil.build(dbSource)
        );
    }

    @Test
    void shouldBuildUrlWithoutDatabaseName() {
        DbSource dbSource = new DbSource();
        dbSource.setHost("localhost");
        dbSource.setPort(3306);

        assertEquals(
                "jdbc:mysql://localhost:3306?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                JdbcUrlUtil.build(dbSource)
        );
    }
}
