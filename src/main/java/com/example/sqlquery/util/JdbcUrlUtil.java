package com.example.sqlquery.util;

import com.example.sqlquery.entity.DbSource;

public class JdbcUrlUtil {
    private JdbcUrlUtil() {
    }

    public static String build(DbSource dbSource) {
        String baseUrl = "jdbc:mysql://" + dbSource.getHost() + ":" + dbSource.getPort();
        if (dbSource.getDatabaseName() != null && !dbSource.getDatabaseName().isEmpty()) {
            baseUrl += "/" + dbSource.getDatabaseName();
        }
        return baseUrl + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
