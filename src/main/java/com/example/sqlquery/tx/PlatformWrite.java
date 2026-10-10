package com.example.sqlquery.tx;

/**
 * 一次「平台库写」—— 会被 {@link PlatformWriteBuffer} 收集、由 {@link PlatformWriteFlusher} 统一提交。

 * <h2>为什么要抽象成对象而不是直接写库</h2>

 * <p>因为一次查询请求会写<b>两张平台表</b>：`audit_log`（审计切面）与 `query_history`（查询历史）。
 * 它们各自提交 = 一次请求 <b>2 次提交 = 4 次 fsync</b>（本项目跑的是 MySQL 双1）。
 * 实测过这笔账：双1 逐行提交 <b>175 行/秒</b>，而双1 单事务 <b>7,053 行/秒</b> ——
 * <b>想快，正解是减少提交次数，而不是牺牲耐久性。</b>

 * <p>所以把「要写什么」和「什么时候提交」拆开：写在请求过程中被收集，
 * 请求结束时由 {@link PlatformWriteFlusher} 在<b>一个事务</b>里提交。
 */
public interface PlatformWrite {

    /**
     * 名字：只用于日志与指标，**必须是固定的几个值**（不要拼动态字符串）。
     */
    String name();

    /** 真正写库。抛异常表示这次写失败 —— 由 flusher 决定怎么处置（见那边的注释）。 */
    void write();

    /**
     * 写失败时记什么。
     *
     * <p>不同的写「失败的后果」不一样，所以不能由 flusher 统一处理：
     * 审计失败是<b>安全审计缺口</b>（要 critical 级别的计数），
     * 历史失败只是<b>附属品没写进去</b>（业务结果不受影响，WARN 即可）。
     */
    void onFailure(Exception cause);
}
