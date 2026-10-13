package com.example.sqlquery.domain;

/**
 * SQL 审核工单状态。
 *
 * <h2>状态机（合法迁移只有这几条）</h2>
 *
 * <pre>
 *   PENDING ──审批人批准──▶ APPROVED ──消费者认领──▶ EXECUTING ──成功──▶ EXECUTED
 *      │                                                └──失败──▶ FAILED
 *      └──审批人驳回──▶ REJECTED
 *
 *   APPROVED / EXECUTING ──补偿任务──▶ FAILED
 * </pre>
 *
 * <p><b>和导出任务的状态机是同构的</b>（{@link ExportStatus}）——
 * 因为两者面对的是同一类问题："人工触发 → 异步执行 → 结果落库"，
 * 而且都要处理"重复投递"和"卡死没人管"。
 * 这个同构不是巧合：**只要一条链路上有"异步 + 外部组件"，状态机就长这样。**
 *
 * <p>⚠️ 和导出不同的是，这里多了一个 <b>REJECTED</b>：
 * 导出任务是"用户要了就跑"，而工单是"要经过另一个人的判断"。
 * 驳回也是一个<b>终态</b>，不是"失败" —— 语义上完全不同，
 * 混在一起会让统计（"多少工单被驳回了"）做不出来。
 */
public final class ReviewStatus {

    private ReviewStatus() {
    }

    /** 已提交，等待审批。 */
    public static final String PENDING = "PENDING";

    /** 审批通过，等待执行。 */
    public static final String APPROVED = "APPROVED";

    /** 被驳回（终态）。 */
    public static final String REJECTED = "REJECTED";

    /** 已被消费者认领，正在执行。 */
    public static final String EXECUTING = "EXECUTING";

    /** 执行成功（终态）。 */
    public static final String EXECUTED = "EXECUTED";

    /** 执行失败（终态）。 */
    public static final String FAILED = "FAILED";

    /** 还没结束、需要轮询或补偿的状态。 */
    public static boolean isOpen(String status) {
        return PENDING.equals(status) || APPROVED.equals(status) || EXECUTING.equals(status);
    }
}
