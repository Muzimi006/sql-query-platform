package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExportTaskVO {

    private Long id;

    private Long dataSourceId;

    private String sqlText;

    private String status;

    private String filePath;

    private Long rowCount;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
