package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QueryHistoryVO {

    private Long id;

    private Long dataSourceId;

    private String sqlText;

    private String status;

    private Long costMs;

    private String errorMessage;

    private LocalDateTime createdAt;
}
