package com.example.sqlquery.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批决定（批准 / 驳回）。
 *
 * <p><b>为什么这里没有 {@code sql} 字段</b>：
 * 审批通过之后，系统执行的是<b>工单里存的那条 SQL</b>，不是前端再传一次的。
 * 如果这里允许传 SQL，就会变成"审批人看了 A、实际执行了 B"——
 * 审批流程的意义就完全没有了。这个决定在 DTO 的<b>结构上</b>被固定下来，
 * 而不是靠"开发者记得别用前端的 SQL"。
 */
@Data
public class SqlReviewDecisionDTO {

    /**
     * 审批意见。
     *
     * <p>批准时可选，<b>驳回时必填</b> —— 校验在服务层做。
     * 理由是：批准不写意见只是"少了一句说明"，
     * 而<b>驳回不写理由，申请人完全不知道该改什么</b>，只能反复提交。
     */
    // 列宽是 VARCHAR(500)。审批意见是自由文本，粘贴一段带格式的说明
    // 很容易超过 —— 不设上界的话，用户看到的是"系统异常"而不是"意见太长了"
    @Size(max = 500, message = "审批意见不能超过 500 个字符")
    private String comment;
}
