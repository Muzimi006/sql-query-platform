package com.example.sqlquery.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("export_task")
public class ExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long dataSourceId;

    private String sqlText;

    private String filePath;

    private String status;

    /**
     * 被消费者<b>认领</b>的时刻（PENDING → RUNNING 时写入）。
     *
     * <p>为什么要单独一列，而不是用 {@code createdAt}：
     * 卡死判定要回答的是"<b>认领之后</b>过了多久"，而 createdAt 是"创建之后过了多久" ——
     * 后者把<b>排队时间</b>也算进去了。
     *
     * <p>队列积压时这个差别是致命的：「排队 6 分钟 + 执行 5 分钟」的正常任务
     * 会被判成卡死（阈值 10 分钟），补偿任务标 FAILED，消费者随后又写回 SUCCESS ——
     * 用户看到"导出成功"却配着一句"已由补偿任务标记为失败"。
     *
     * <p>和 {@code finishedAt} 一样，它是 {@code UPDATE} 而不是 {@code INSERT_UPDATE}：
     * 任务刚创建时本来就还没被认领。
     */
    private LocalDateTime startedAt;

    private Long rowCount;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 任务完成时间。
     *
     * <p>只能是 {@link FieldFill#UPDATE}，不能是 {@code INSERT_UPDATE}：
     * 带上 INSERT 意味着"这个字段要在插入时由框架维护"，
     * 而任务刚创建时它<b>本来就应该是 NULL</b>（还没跑完）。
     * 标成 INSERT_UPDATE 会让该列出现在 INSERT 语句里被显式写成 NULL，
     * 语义与实现不一致。
     */
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime finishedAt;

}
