package com.example.sqlquery.config;

import com.example.sqlquery.common.UserRole;
import com.example.sqlquery.util.SqlType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「角色 → 允许执行的 SQL 类型」的配置。
 *
 * <h2>为什么要做成配置而不是写死在代码里</h2>
 *
 * <p>权限矩阵是<b>部署相关</b>的：同一个系统，给内部用和给外部用，
 * 允许的角色和语句类型不可能一样。写死在代码里意味着每次调整都要改代码、重新发版，
 * 而权限调整往往是运维在紧急情况下要做的动作。
 *
 * <h2>默认值为什么是这样</h2>
 *
 * <ul>
 *   <li>{@link UserRole#USER} 只有 {@code SELECT} —— 平台的主场景是"查询与审核"；</li>
 *   <li>{@link UserRole#ADMIN} 额外有 INSERT / UPDATE / DELETE；</li>
 *   <li><b>DDL 默认不在任何角色里</b>：{@code DROP TABLE} 这类语句在这个平台上没有正当用途，
 *       要开必须由部署方显式配置，而不是"因为是管理员所以顺手给了"。</li>
 * </ul>
 *
 * <p>⚠️ 这里配的只是<b>应用层</b>的白名单。真正的兜底仍然是数据源侧的数据库账号权限 ——
 * 应用层无论被怎么绕过，最终能在数据库上做的事情依然被账号限制着。
 */
@Component
@ConfigurationProperties(prefix = "platform.sql-permission")
public class SqlPermissionProperties {

    /**
     * 角色 → 允许的语句类型集合。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：{@code Map.of} 是**不可变**的，
     * 而 Spring Boot 绑定配置时需要往里面 put —— 用不可变实现会在绑定时直接抛异常。
     * 这个坑很隐蔽，因为只有在"配置文件里真的写了这一项"时才会触发。
     */
    private Map<String, Set<SqlType>> roleWhitelist = defaultWhitelist();

    private static Map<String, Set<SqlType>> defaultWhitelist() {
        Map<String, Set<SqlType>> defaults = new LinkedHashMap<>();
        defaults.put(UserRole.USER, EnumSet.of(SqlType.SELECT));
        defaults.put(UserRole.ADMIN, EnumSet.of(
                SqlType.SELECT, SqlType.INSERT, SqlType.UPDATE, SqlType.DELETE));
        return defaults;
    }

    public Map<String, Set<SqlType>> getRoleWhitelist() {
        return roleWhitelist;
    }

    public void setRoleWhitelist(Map<String, Set<SqlType>> roleWhitelist) {
        this.roleWhitelist = roleWhitelist;
    }
}
