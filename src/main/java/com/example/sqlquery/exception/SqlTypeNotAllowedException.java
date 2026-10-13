package com.example.sqlquery.exception;

import com.example.sqlquery.util.SqlType;

import java.util.Set;

/**
 * 语句类型不在白名单里。
 *
 * <h2>为什么值得单开一个异常类型</h2>
 *
 * <p>它和「SQL 里有危险函数」「访问了系统库」一样都是拒绝，但<b>拒绝的理由完全不同</b>，
 * 而这个区别在工单链路上会变成两句意思相反的话：
 *
 * <ul>
 *   <li>用户往工单接口提交一条 {@code SELECT} —— 他真正需要听到的是
 *       「这是查询，不用审批，直接去查询页跑」；</li>
 *   <li>用户提交一条含 {@code sleep(1)} 的 {@code UPDATE} —— 他需要听到的是
 *       「这条 SQL 不允许执行」。</li>
 * </ul>
 *
 * <p>如果两种情况都只抛一句「没有权限执行该类型SQL」，工单接口就只能原样转发它，
 * 于是<b>一条普通的查询会被告知「没有权限」，而用户明明有查询权限</b>。
 * 只有把具体类型带出来，上层才能在正确的地方说正确的话。
 *
 * <h2>为什么它必须是 BusinessException 的子类</h2>
 *
 * <p>这样全局异常处理器不需要任何改动，HTTP 语义也不变（200 + code + 中文 message）。
 * 多出来的只是一个「可以精确 catch 的标签」——
 * 调用方不想区分时照旧按 BusinessException 处理，想区分时才 catch 具体子类。
 */
public class SqlTypeNotAllowedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 实际解析出来的语句类型。 */
    private final transient SqlType actualType;

    /** 当时用的白名单，进日志用。 */
    private final transient Set<SqlType> allowedTypes;

    public SqlTypeNotAllowedException(SqlType actualType, Set<SqlType> allowedTypes) {
        super("没有权限执行该类型SQL");
        this.actualType = actualType;
        this.allowedTypes = allowedTypes == null ? Set.of() : Set.copyOf(allowedTypes);
    }

    public SqlType getActualType() {
        return actualType;
    }

    public Set<SqlType> getAllowedTypes() {
        return allowedTypes;
    }
}
