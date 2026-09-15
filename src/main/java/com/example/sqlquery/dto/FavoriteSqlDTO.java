package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FavoriteSqlDTO {

    private Long dataSourceId;

    @NotBlank(message = "SQL不能为空")
    private String sqlText;

    private String remark;

}
