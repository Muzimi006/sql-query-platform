package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DbSourceVO {

    private Long id;

    private String name;

    private String type;

    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
