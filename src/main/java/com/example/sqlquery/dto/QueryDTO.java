package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QueryDTO {

    @NotNull(message = "数据源不能为空")
    private Long dataSourceId;

    @NotBlank(message =  "SQL不能为空")
    private String sql;
}
