package com.example.sqlquery.common;

/**
 * 用户角色。
 *
 * <p>对应 {@code user.role} 字段。抽成常量的理由和 {@link DbSourceStatus} 一样：
 * 同一批字面量在「注册时赋角色」「登录后签发 token」「按角色取权限白名单」三处都要用，
 * 写散了对不上也不会有编译错误。
 *
 * <p>⚠️ 它同时是 {@code platform.sql-permission.role-whitelist} 配置里的 <b>key</b>。
 * 改这里的值必须同步改配置，否则用户拿到的角色在权限表里查不到 ——
 * 按 {@link com.example.sqlquery.service.SqlPermissionService} 的默认拒绝策略，
 * 结果是<b>这个角色的用户一个查询都执行不了</b>。
 */
public final class UserRole {

    private UserRole() {
    }

    /** 普通用户：只能查（SELECT）。 */
    public static final String USER = "USER";

    /** 管理员：在查询之外额外被允许写入类语句，具体范围由配置决定。 */
    public static final String ADMIN = "ADMIN";

    /**
     * 判断角色是不是管理员。
     *
     * <p>写成静态方法而不是各处自己 {@code ADMIN.equals(role)}，是为了把
     * 「大小写不敏感」「role 为 null 时怎么办」这两件事<b>只决定一次</b>。
     * 散着写的结果通常是某几处记得 trim/toUpperCase、另几处忘了。
     *
     * <p>{@code null} 安全：{@code ADMIN.equalsIgnoreCase(null)} 返回 {@code false}
     * （{@code String.equalsIgnoreCase} 对 null 参数有显式判断，不会 NPE）。
     * 这与项目里 fail-closed 的方向一致 —— 拿不准就当不是管理员。
     *
     * <p>⚠️ 它<b>只</b>用来判断「有没有资格审批别人的工单」这类管理动作。
     * 能不能执行某类 SQL 是另一回事，走
     * {@link com.example.sqlquery.service.SqlPermissionService} 的白名单。
     */
    public static boolean isAdmin(String role) {
        return role != null && ADMIN.equalsIgnoreCase(role.trim());
    }
}
