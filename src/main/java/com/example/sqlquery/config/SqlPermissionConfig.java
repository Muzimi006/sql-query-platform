package com.example.sqlquery.config;

import com.example.sqlquery.util.SqlType;

import java.util.Map;
import java.util.Set;

public class SqlPermissionConfig {

    private SqlPermissionConfig() {
    }

    private static final Map<String, Set<SqlType>> ROLE_WHITELIST = Map.of(
            "USER", Set.of(SqlType.SELECT),
            "ADMIN", Set.of(SqlType.SELECT)
    );

    public static Set<SqlType> getByRole(String role) {
        return ROLE_WHITELIST.getOrDefault(role, Set.of(SqlType.SELECT));
    }
}
