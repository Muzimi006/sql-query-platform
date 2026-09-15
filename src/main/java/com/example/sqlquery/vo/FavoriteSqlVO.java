package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FavoriteSqlVO {

    private Long id;

    private Long dataSourceId;

    private String sqlText;

    private String remark;

    private LocalDateTime createdAt;
}
