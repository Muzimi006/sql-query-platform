package com.example.sqlquery.domain;

/**
 * 导出任务状态。
 *
 * <p>对应 {@code export_task.status} 字段。抽出来的理由和 {@link DbSourceStatus} 一样：
 * 同一批字面量在「创建任务」「消费者认领」「消费者写结果」「补偿任务」四处都要用，
 * 散着写字符串时，改一处漏一处不会有任何编译错误 —— 只会让状态机悄悄对不上。
 *
 * <p><b>状态机</b>（合法迁移只有这三条）：
 * <pre>
 *   PENDING --消费者认领--> RUNNING --成功--> SUCCESS
 *                                   \--失败--> FAILED
 *
 *   PENDING / RUNNING --补偿任务--> FAILED
 * </pre>
 * 任何"从 SUCCESS 再变回 RUNNING"的路径都不存在，这正是重复投递能被挡住的原因。
 */
public final class ExportStatus {

    private ExportStatus() {
    }

    /** 已创建、等待消费者处理。 */
    public static final String PENDING = "PENDING";

    /** 已被某个消费者认领、正在执行。 */
    public static final String RUNNING = "RUNNING";

    /** 执行成功，文件已落盘（是否还能下载还要看 file_path 是否被清理任务置空）。 */
    public static final String SUCCESS = "SUCCESS";

    /** 执行失败，error_message 里有原因。 */
    public static final String FAILED = "FAILED";
}
