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

    /**
     * 客户端提交的明文密码，服务端加密后写入 {@code DbSource.passwordEncrypted}。
     *
     * <p>⚠️ <b>刻意没有 {@code @NotBlank}</b>：这个 DTO 同时用于「新增」和「编辑」，
     * 而编辑时**密码留空表示不修改**（出参永不下发密码，要求用户改个名字就重输一遍密码是不合理的）。
     * 于是「新建必须填密码」这条规则下沉到服务层（{@code addDbSource}），
     * 那里才是唯一知道"这次是新增还是编辑"的地方。
     *
     * <p>代价说清楚：新建时漏填密码，报错来自服务层（业务失败 + 一句人话），
     * 而不是参数校验层。两者对用户都是"业务失败 + 提示"，行为一致。
     */
    private String password;
}
