package com.example.sqlquery.common;

/**
 * 工单写语句的「前镜像留存」结果。
 *
 * <h2>三种状态必须分清楚，因为它们对运维的含义完全不同</h2>
 *
 * <table border="1">
 *   <caption>状态语义</caption>
 *   <tr><th>状态</th><th>含义</th><th>运维该做什么</th></tr>
 *   <tr><td>{@link #CAPTURED}</td><td>前镜像拿到了，反向 SQL 也生成了</td>
 *       <td>出事时按 {@code inverse_sql} 核对后回滚</td></tr>
 *   <tr><td>{@link #SKIPPED}</td><td><b>这条语句的形态就不支持留存</b>
 *       （多表 UPDATE、DDL、目标表没主键、改动行数超过留存上限）</td>
 *       <td>知道"这次没有回滚依据"，要靠备份/binlog —— <b>不是故障，是能力边界</b></td></tr>
 *   <tr><td>{@link #FAILED}</td><td>取像过程中出了<b>意外</b>错误</td>
 *       <td>是真的异常，要看日志</td></tr>
 * </table>
 *
 * <p>⚠️ 把 SKIPPED 和 FAILED 混成一个值会让告警失去意义：
 * "有一条 DDL 工单没有反向 SQL"是正常现象，
 * 而"取像抛异常了"需要人去看 —— 两者的处置完全不同。
 */
public final class UndoStatus {

    /** 前镜像与反向 SQL 都已留存。 */
    public static final String CAPTURED = "CAPTURED";

    /** 形态不支持留存（不是错误）。 */
    public static final String SKIPPED = "SKIPPED";

    /** 取像过程中出了意外错误。 */
    public static final String FAILED = "FAILED";

    private UndoStatus() {
    }
}