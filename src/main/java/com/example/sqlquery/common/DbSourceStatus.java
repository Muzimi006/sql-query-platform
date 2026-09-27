package com.example.sqlquery.common;

/**
 * 数据源状态。
 *
 * <p>对应 {@code data_source.status} 字段（TINYINT），也对应前端展示的「启用 / 停用」。
 * 抽成常量是因为这个值在「新增数据源」「启用/停用」「执行 SQL 前校验」三处都要用，
 * 散落写 {@code 1} / {@code 0} 就是三处魔法数字。
 */
public final class DbSourceStatus {

    private DbSourceStatus() {
    }

    /** 启用：可以执行 SQL、可以导出。 */
    public static final int ENABLED = 1;

    /** 停用：保留配置，但禁止使用。 */
    public static final int DISABLED = 0;
}
