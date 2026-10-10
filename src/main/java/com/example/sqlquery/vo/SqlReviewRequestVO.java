package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单的出参。
 *
 * <p>注意 {@link #involvedTables} 在库里存的是逗号分隔的字符串，
 * 出口转成数组 —— <b>存储格式是内部细节，不该泄漏给前端</b>。
 */
@Data
public class SqlReviewRequestVO {

    private Long id;

    private Long dataSourceId;

    private String sqlText;

    private String sqlType;

    private String status;

    /** 这条 SQL 会动哪几张表。 */
    private List<String> involvedTables;

    /** EXPLAIN 预估的<b>扫描</b>行数。可能为 null（预估失败或该方言不支持）。 */
    private Long estimatedRows;

    /** 预估的补充说明 —— 审批人需要知道这个数字可不可信。 */
    private String estimateNote;

    /** HUMAN / AI。 */
    private String generatedBy;

    /** AI 生成时用户的原话。 */
    private String generationPrompt;

    private Long reviewerId;

    private String reviewComment;

    private LocalDateTime reviewedAt;

    private Long affectedRows;

    private String errorMessage;

    private LocalDateTime executedAt;

    /**
     * ⭐ 前镜像留存状态（见 {@code UndoStatus}）：CAPTURED / SKIPPED / FAILED。
     *
     * <p>它回答的是「这次执行<b>有没有回滚依据</b>」——
     * 在它之前，这个问题在平台上根本没有答案。
     */
    private String undoStatus;

    /** 留存了多少行的前镜像。 */
    private Integer undoRowCount;

    /** 没有留存下来时的原因（人话，直接展示）。 */
    private String undoReason;

    /**
     * 由前镜像生成的反向 SQL。
     *
     * <p>⚠️ <b>只用于给人核对</b>，平台上没有任何接口会执行它 ——
     * 要回滚就把这段 SQL 复制出来，<b>作为一条新工单提交</b>，
     * 走完全相同的审批路径。理由见 {@code SqlUndoRecorder} 的类注释。
     */
    private String inverseSql;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
