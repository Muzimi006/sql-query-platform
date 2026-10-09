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
