package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交一条审核工单。
 *
 * <p>⚠️ <b>这里没有 {@code generatedBy} 字段，是刻意的。</b>
 * 来源由服务端根据"走的是哪个入口"来标记，
 * 而不是让客户端自己声明自己是 AI —— 后者等于把审计字段交给调用方随便填。
 */
@Data
public class SqlReviewSubmitDTO {

    @NotNull(message = "数据源不能为空")
    private Long dataSourceId;

    // 上界不是形式主义：列类型是 TEXT（64KB 字节），
    // 超长时 MySQL 严格模式抛 DataIntegrityViolationException ——
    // 它既不是 BusinessException 也不是 DuplicateKeyException，
    // 会一路掉进全局兜底变成"系统异常"，用户完全不知道是自己贴太长了
    @NotBlank(message = "SQL不能为空")
    @Size(max = 20000, message = "SQL 不能超过 20000 个字符")
    private String sql;

    /**
     * 幂等键（可选）。
     *
     * <p>用户在页面上双击"提交"、或者网络超时后重试，都会产生两次请求。
     * 带上同一个 token，服务端靠唯一索引去重，**不会产生两条一模一样的工单**。
     *
     * <p>不传的话不去重（允许同一个 SQL 提交多次，那本身是合法的）。
     */
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    private String requestToken;

    /**
     * 如果这条 SQL 是 AI 根据自然语言生成的，把用户当时的问题带上。
     *
     * <p>它有两个用途：给审批人看"意图对不对"；以及在审计里回答
     * "这条 SQL 是照着什么需求写出来的"。
     */
    @Size(max = 1000, message = "原始需求描述不能超过 1000 个字符")
    private String generationPrompt;
}
