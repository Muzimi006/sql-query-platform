package com.example.sqlquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 接口文档（springdoc / Swagger UI）**默认必须是关的**。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code JwtInterceptor} 只注册在 {@code /api/**} 上（见 {@code WebMvcConfig}），
 * 而接口文档的两个入口 {@code /v3/api-docs} 与 {@code /swagger-ui/**} <b>不在这个路径下</b> ——
 * 也就是说它们<b>根本不会经过鉴权</b>。2026-10 实测：不登录就能拿到一份完整的 OpenAPI 文档
 * （30 个接口的路径、参数、DTO 字段、校验规则）。
 *
 * <p>泄漏的是"接口的面貌"，不是数据（真正的业务接口仍然要 JWT）。但接口面貌本身省掉了
 * 攻击者枚举和试错的成本，所以它的默认值只有一个正确选择：<b>关</b>。
 *
 * <p>⚠️ 原来的默认值是 {@code true} —— 等于"默认把接口面貌公开出去，只有读过 yml 的人才知道要关"。
 * 这与项目里其它默认值的取向相反（NL2SQL 默认关、DDL 默认谁都不给、留痕清理默认关、
 * 监控端点只监听本机）：<b>能关的东西默认关，要用的人显式打开</b>。
 *
 * <h2>为什么写成测试而不是"在 yml 里写一行注释"</h2>
 *
 * <p>注释不会拦人：下一个人觉得"本地调试方便"把它改回 {@code true} 再提交，不会有任何东西变红。
 * 这个测试把"默认值必须是 false"变成一条会变红的断言 —— 想改默认值，就得先解释为什么。
 * （同一个取向见 {@code ExportTimeBudgetConfigTest}：不变式写成断言，而不是启动日志。）
 */
class OpenApiExposureConfigTest {

    private static final String CONFIG = "/application.yml";

    private static Map<String, Object> yaml() {
        try (InputStream in = OpenApiExposureConfigTest.class.getResourceAsStream(CONFIG)) {
            assertNotNull(in, "找不到 " + CONFIG + "：测试依赖 src/main/resources 在测试类路径上");
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + CONFIG + " 失败", e);
        }
    }

    /** 按 a.b.c 取值；缺任何一层都返回 null（调用方负责断言）。 */
    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> root, String path) {
        Object cur = root;
        for (String key : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(key);
        }
        return cur;
    }

    @Test
    @DisplayName("springdoc 的两个入口默认关闭：只有显式设 SPRINGDOC_ENABLED=true 才开")
    void apiDocsAndSwaggerUiMustBeOffByDefault() {
        assertEquals("${SPRINGDOC_ENABLED:false}", get(yaml(), "springdoc.api-docs.enabled"),
                "接口文档的默认值必须是 false —— 它不在 /api/** 下，不受 JwtInterceptor 保护");
        assertEquals("${SPRINGDOC_ENABLED:false}", get(yaml(), "springdoc.swagger-ui.enabled"),
                "文档页面本身也要一起关：api-docs 关了它没内容可读，但页面存在本身就是一处暴露面");
    }
}
