package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用一句中文需求换一条待审 SQL。
 *
 * <p>这里没有 {@code sql} 字段，也没有 {@code generatedBy} ——
 * SQL 是模型产生的，来源是服务端标记的。调用方不能自己声明"我这是 AI 生成的"，
 * 那样审计字段就形同虚设。
 */
@Data
public class Nl2SqlRequestDTO {

    @NotNull(message = "数据源不能为空")
    private Long dataSourceId;

    /**
     * 业务同学的原话，例如「把灵隐寺 3 月 8 日的余票调整到 500 张」。
     *
     * <p>限长 500：这句话<b>会原样进提示词、也会原样存进工单</b>给审批人看。
     * 不限长的话，一个几万字的输入就能把单次调用成本顶上去。
     */
    @NotBlank(message = "请描述你想要的数据变更")
    @Size(max = 500, message = "描述不能超过 500 字")
    private String prompt;

    /** 幂等键（可选）。与手写工单共用同一套去重机制。 */
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    private String requestToken;
}
