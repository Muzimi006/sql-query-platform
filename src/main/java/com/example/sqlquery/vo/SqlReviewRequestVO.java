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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
