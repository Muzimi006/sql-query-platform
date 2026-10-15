package com.example.sqlquery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（OpenAPI 3）。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>项目的学习文档讲的是"为什么这么设计"，那是给**读代码的人**看的；
 * 而"这个接口的请求体到底有哪几个字段、哪个必填"是给**调接口的人**看的。
 * 两者不冲突，但后者手写一定会和代码漂移 —— 所以让它从代码里生成。
 *
 * <h2>⚠️ 安全上的边界（必须说清楚）</h2>
 *
 * <p>{@code JwtInterceptor} 只覆盖 {@code /api/**}，而接口文档的两个路径
 * （{@code /v3/api-docs}、{@code /swagger-ui/**}）**不在这个范围里** ——
 * 也就是说它们是<b>公开可访问</b>的。
 *
 * <p>泄漏的是<b>接口的面貌</b>（路径、参数名、DTO 字段名、校验规则），
 * <b>不是数据</b>：真正的业务接口仍然要求 JWT，没有 token 一样调不通
 * （Swagger UI 上的 "Authorize" 按钮就是给这件事用的）。
 *
 * <p>但"接口面貌"本身对攻击者是有价值的（省掉了枚举和试错的成本）。
 * 所以它必须<b>默认关闭</b> —— 2026-10 起 {@code springdoc.api-docs.enabled} 与
 * {@code springdoc.swagger-ui.enabled} 的默认值都是 <b>{@code false}</b>，
 * 本地要看文档就设 {@code SPRINGDOC_ENABLED=true} 再启动。
 *
 * <p>⚠️ <b>原来的默认值是 {@code true}</b>（"可关，但默认开着"）—— 那等于把"接口面貌默认公开"
 * 当成默认行为，只有读过 yml 的人才知道要关。这与项目里其它默认值（NL2SQL 默认关、
 * DDL 默认谁都不给、留痕清理默认关、监控端点只监听本机）的方向相反，所以对齐成
 * "<b>默认不暴露，要用的人显式打开</b>"；这条不变式由 {@code OpenApiExposureConfigTest} 守着，
 * 不靠注释。—— 起因与实测见问题修复档案 <b>W-07</b>。
 *
 * <h2>为什么要显式声明 Bearer 安全方案</h2>
 *
 * <p>不声明的话，Swagger UI 上不会出现 "Authorize" 按钮 ——
 * 于是所有接口在文档里点一下都是 401，文档就废了。
 * 声明之后可以先在 UI 上贴一个 token，后续请求都会带上它。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI platformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SQL 查询与审核平台 API")
                        .version("0.0.1")
                        .description("""
                                在线 SQL 查询与审核平台。
                                
                                业务接口除注册 / 登录 / 登出外，都需要在请求头带
                                Authorization: Bearer <token>。
                                
                                ⚠️ 本项目所有接口都返回 HTTP 200，业务结果在响应体的 code 里
                                （200 表示成功，500 表示业务失败，message 是给用户看的中文提示）。
                                所以只看 HTTP 状态码判断不了成功与否。
                                """))
                // 声明 Bearer 方案 → Swagger UI 上会出现 Authorize 按钮
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口拿到的 token，直接粘贴即可（不用加 Bearer 前缀）")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
