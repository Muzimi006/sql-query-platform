package com.example.sqlquery.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 审核工单。
 *
 * <p>写语句（INSERT / UPDATE / DELETE / DDL）不再立即执行，而是先落一条这样的工单，
 * 由审批人看过影响面之后再决定执不执行。
 *
 * <p><b>为什么"能不能执行"和"该不该批准"要分成两层</b>：
 * {@code SqlPermissionService} 回答的是前者（这个角色有没有资格执行写语句），
 * 而这一层回答的是后者（这一条具体的 SQL 该不该放行）。
 * 票务、文物这类业务里，一条 UPDATE 改错就是钱和档案的问题 ——
 * 光有"角色权限"不够，还需要"另一个人的判断"。
 */
@Data
@TableName("sql_review_request")
public class SqlReviewRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人。 */
    private Long userId;

    private Long dataSourceId;

    /**
     * 待执行的 SQL。
     *
     * <p>⚠️ <b>执行时以这一列存的为准，绝不接受前端再传一次。</b>
     * 否则会出现"审批人看到的是一条、实际执行的是另一条"——
     * 这是审批类系统最经典的安全漏洞（TOCTOU）。
     */
    private String sqlText;

    private String sqlType;

    private String status;

    /** AST 解析出的表名，逗号分隔 —— 给审批人看"这条 SQL 会动哪几张表"。 */
    private String involvedTables;

    /** EXPLAIN 预估的<b>扫描</b>行数（注意不是影响行数，字段命名要诚实）。 */
    private Long estimatedRows;

    /** 预估的补充说明：全表扫描、该方言不支持预估、EXPLAIN 失败等。 */
    private String estimateNote;

    /** 来源：{@link com.example.sqlquery.domain.SqlOrigin}。 */
    private String generatedBy;

    /** AI 生成时，用户的原始自然语言问题 —— 审批人靠它判断"意图对不对"。 */
    private String generationPrompt;

    /** 客户端幂等键。同一次提交重试不会产生第二条工单；不传则为 null（不做去重）。 */
    private String requestToken;

    private Long reviewerId;

    private String reviewComment;

    private LocalDateTime reviewedAt;

    private Long affectedRows;

    private String errorMessage;

    private LocalDateTime executedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
