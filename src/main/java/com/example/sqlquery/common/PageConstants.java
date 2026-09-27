package com.example.sqlquery.common;

/**
 * 分页参数的公共约束。
 *
 * <p>上限的意义：{@code /page} 这类接口如果允许任意大的 size，
 * 一次请求就能把整张表捞进内存。查询接口已经有 {@code setMaxRows} 保护，
 * 分页接口必须有一致等级的防护。
 */
public final class PageConstants {

    private PageConstants() {
    }

    /** 单页允许返回的最大条数。 */
    public static final int MAX_PAGE_SIZE = 100;
}
