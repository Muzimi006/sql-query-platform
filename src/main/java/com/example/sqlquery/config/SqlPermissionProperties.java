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

    /**
     * 「可以提交审核工单去申请执行」的语句类型。
     *
     * <h2>为什么它必须和 {@link #roleWhitelist} 分开</h2>
     *
     * <p>这是工单流程里最容易搞错、也最致命的一个点。
     * 直觉上会想"提交工单也用同一个白名单不就行了"，但那样会得到一个死循环：
     * {@link UserRole#USER} 的白名单里只有 {@code SELECT}，
     * 于是 USER <b>连一条 UPDATE 的工单都提交不了</b> ——
     * 而"让没有直接写权限的人能够申请写操作"，恰恰是整个工单系统存在的理由。
     *
     * <p>所以要把两件事分清楚：
     * <ul>
     *   <li><b>能不能申请</b> —— 由本配置决定，默认 INSERT / UPDATE / DELETE；</li>
     *   <li><b>能不能直接执行</b> —— 由 {@link #roleWhitelist} 决定，走
     *       {@code QueryServiceImpl}，与工单无关；</li>
     *   <li><b>工单批了之后能不能执行</b> —— 由"审批人点了通过"这个动作授予，
     *       它是一次性的、绑定到具体一条 SQL 上的，不会顺带把角色升级成可写。</li>
     * </ul>
     *
     * <h2>为什么 DDL 默认不在里面</h2>
     *
     * <p>与 {@link #roleWhitelist} 里 DDL 默认谁都不给是同一个理由：
     * {@code DROP TABLE} / {@code TRUNCATE} 这类语句没有"审核一下就能放行"的正当场景 ——
     * 它们不是数据订正，是结构变更，应该走 DBA 流程而不是业务工单。
     * 真需要时由部署方显式加，而不是默认开放。
     *
     * <p>⚠️ 放宽的仅仅是<b>语句类型</b>。解析、AST 危险函数检查、系统库检查、
     * 字符串兜底这四道防线在提交和驳回之后的执行阶段<b>一道都不会少</b> ——
     * 可申请的是"写语句"，不是"危险语句"。
     */
    private Set<SqlType> reviewableTypes = defaultReviewableTypes();

    private static Set<SqlType> defaultReviewableTypes() {
        return EnumSet.of(SqlType.INSERT, SqlType.UPDATE, SqlType.DELETE);
    }

    public Map<String, Set<SqlType>> getRoleWhitelist() {
        return roleWhitelist;
    }

    public void setRoleWhitelist(Map<String, Set<SqlType>> roleWhitelist) {
        this.roleWhitelist = roleWhitelist;
    }

    public Set<SqlType> getReviewableTypes() {
        return reviewableTypes;
    }

    public void setReviewableTypes(Set<SqlType> reviewableTypes) {
        this.reviewableTypes = reviewableTypes;
    }
}
