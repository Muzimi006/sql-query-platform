package com.example.sqlquery.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("query_history")
public class QueryHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long dataSourceId;

    private String sqlText;

    private String status;

    private Long costMs;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
