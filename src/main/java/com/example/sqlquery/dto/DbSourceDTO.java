package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DbSourceDTO {

    @NotBlank(message = "数据源名称不能为空")
    private String name;

    @NotBlank(message = "数据库类型不能为空")
    private String type;

    @NotBlank(message = "主机地址不能为空")
    private String host;

    @NotNull(message = "端口不能为空")
    private Integer port;

    private String databaseName;

    @NotBlank(message = "数据库用户名不能为空")
    private String username;

    @NotBlank(message = "数据库密码不能为空")
    private String passwordEncrypted;
}
