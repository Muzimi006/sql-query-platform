package com.example.sqlquery.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String nickname;

    /**
     * 管理员邀请码（可选，普通注册不填）。
     *
     * <p>它与服务端的 {@code platform.admin-register-code} 比对，匹配才授予 ADMIN 角色。
     * 用"邀请码"而不是"把某个用户名写进配置"，是因为后者<b>可以被抢注</b> ——
     * 只要没人先注册那个名字，任何人都能拿到管理员权限。
     *
     * <p>⚠️ 这个字段<b>不能</b>被写进日志或审计明细。审计切面记录的是请求 URI 与方法签名，
     * 不含请求体，所以当前是安全的；如果以后给审计加上"记录请求参数"，必须把它排除掉。
     */
    private String adminCode;
}
