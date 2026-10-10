package com.example.sqlquery.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口文档（OpenAPI）真的能生成，并且和实现**保持同步**。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>本项目把"接口的字段级契约"交给了 springdoc 自动生成，而不是手写一份 ——
 * 因为手写的副本一定会漂移，**而且漂移时不会报错**，它只是安静地变成错的。
 *
 * <p>但"自动生成"本身也可能坏掉，而且坏法很隐蔽：
 * <ul>
 *   <li>依赖版本和 Spring Boot 大版本不匹配（springdoc 2.x ↔ Boot 3、3.x ↔ Boot 4）——
 *       <b>不会编译失败</b>，会在启动阶段崩，或者更糟：静默地少生成一部分；</li>
 *   <li>有人把 `springdoc.api-docs.enabled` 关掉之后忘了打开；</li>
 *   <li>新增了接口，但 {@code 11-接口清单与契约.md} 没跟着更新。</li>
 * </ul>
 *
 * <p>最后那条是本类最有价值的地方：**它把"文档要不要更新"从人的记性
 * 变成了一个会变红的断言。**
 *
 * <p>用真实端口（{@code RANDOM_PORT}）而不是 MockMvc：要验的恰恰是
 * "这个端点**真的被 serve 出来**了"，MockMvc 会绕过一部分 MVC 装配。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIntegrationTest {

    /**
     * 当前接口总数。
     *
     * <p>⚠️ <b>新增或删除接口时，这个数字和
     * {@code D:\sql查询项目学习\学习-按板块\11-接口清单与契约.md} 都要一起改。</b>
     * 测试变红不是目的，它是提醒你"还有一个地方需要同步"。
     */
    private static final int EXPECTED_OPERATIONS = 30;

    /**
     * 🔒 仅 ADMIN 的三个接口。
     *
     * <p>⚠️ <b>权限规则写在服务层，不写在注解上</b>（那是刻意的：
     * 放在注解上，以后多一个调用方就多一条绕过检查的路径）。
     * 代价是<b>自动生成的文档看不出来</b> —— 所以必须由人补上说明，
     * 而"人补的东西会漏"正是本测试要挡的事。
     */
    private static final List<String> ADMIN_ONLY_OPERATIONS = List.of(
            "GET /api/sql-review/pending",
            "POST /api/sql-review/{id}/approve",
            "POST /api/sql-review/{id}/reject");

    /** 全项目仅有的三个不需要 token 的接口。 */
    private static final List<String> PUBLIC_OPERATIONS = List.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "POST /api/auth/logout");

    /** 每个业务域至少有一个接口出现在文档里（防止某个 Controller 整个没被扫到）。 */
    private static final List<String> REQUIRED_PATH_PREFIXES = List.of(
            "/api/auth/", "/api/datasource", "/api/query/", "/api/history",
            "/api/favorite", "/api/export", "/api/sql-review/", "/api/nl2sql/");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode fetchApiDocs() throws Exception {
        String json = RestClient.create()
                .get()
                .uri("http://127.0.0.1:" + port + "/v3/api-docs")
                .retrieve()
                .body(String.class);
        assertNotNull(json, "/v3/api-docs 返回了空响应体");
        return objectMapper.readTree(json);
    }

    /** 把 paths 下每个 path 的方法名收集起来 —— 一个 path 可能有多个方法。 */
    private List<String> collectOperations(JsonNode root) {
        List<String> operations = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(pathEntry -> {
            pathEntry.getValue().fieldNames().forEachRemaining(method -> {
                if (List.of("get", "post", "put", "delete", "patch").contains(method)) {
                    operations.add(method.toUpperCase() + " " + pathEntry.getKey());
                }
            });
        });
        return operations;
    }

    @Test
    @DisplayName("/v3/api-docs 必须能被访问，且是一份 OpenAPI 3 文档")
    void apiDocsMustBeServed() throws Exception {
        JsonNode root = fetchApiDocs();

        // 少了这个字段说明拿到的不是 OpenAPI 文档（可能是错误页、或者被别的组件接管了路径）
        String openapi = root.path("openapi").asText("");
        assertTrue(openapi.startsWith("3."),
                "这不是一份 OpenAPI 3 文档，实际 openapi 字段 = " + openapi);
        assertNotNull(root.path("info").path("title").asText(null), "缺少 info.title");
    }

    @Test
    @DisplayName("必须声明 Bearer 安全方案 —— 否则 Swagger UI 上没有 Authorize 按钮，文档等于废的")
    void bearerSchemeMustBeDeclared() throws Exception {
        JsonNode root = fetchApiDocs();

        JsonNode schemes = root.path("components").path("securitySchemes");
        assertTrue(schemes.has("bearer-jwt"),
                "没有声明 bearer-jwt，Swagger UI 上就不会出现 Authorize 按钮，"
                        + "所有接口在文档里点一下都是 401。实际：" + schemes);
        assertEquals("bearer", schemes.path("bearer-jwt").path("scheme").asText());
    }

    @Test
    @DisplayName("⭐ 接口总数变了就变红 —— 提醒同步 11-接口清单与契约.md")
    void operationCountMustMatchTheDocumentedOne() throws Exception {
        List<String> operations = collectOperations(fetchApiDocs());

        assertEquals(EXPECTED_OPERATIONS, operations.size(),
                "接口数量与 11-接口清单与契约.md 记录的不一致。"
                        + "新增/删除接口时，请同时更新那个文档和本测试的 EXPECTED_OPERATIONS。"
                        + "当前实际：" + operations);
    }

    /** 找出所有"说明里带了 ADMIN 标记"的接口。 */
    private List<String> findMarkedAdminOnly(JsonNode root) {
        List<String> marked = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields()
                .forEachRemaining(methodEntry -> {
                    if (!List.of("get", "post", "put", "delete", "patch").contains(methodEntry.getKey())) {
                        return;
                    }
                    String description = methodEntry.getValue().path("description").asText("");
                    if (description.contains("仅 ADMIN")) {
                        marked.add(methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey());
                    }
                }));
        return marked;
    }

    @Test
    @DisplayName("🔒 仅 ADMIN 的接口必须在文档里标注 —— 权限规则不写进注解，自动生成是看不出来的")
    void adminOnlyEndpointsMustBeMarked() throws Exception {
        List<String> marked = findMarkedAdminOnly(fetchApiDocs());

        // 两个方向都断言：
        // ① 该标的都标了（漏标注 → 调用方以为谁都能调）
        // ② 没有多标（多标注 → 文档说谎，把人挡在门外）
        assertEquals(ADMIN_ONLY_OPERATIONS.stream().sorted().toList(),
                marked.stream().sorted().toList(),
                "文档里标了「仅 ADMIN」的接口和实际的 ADMIN 接口对不上。"
                        + "新增管理员接口时，请同步 @Operation(description=...) 和本测试的 EXPECTED 列表。");
    }

    @Test
    @DisplayName("三个公开接口必须显式声明 security: [] —— 否则文档上看起来它们也要 token")
    void publicEndpointsMustDeclareNoSecurity() throws Exception {
        JsonNode root = fetchApiDocs();
        List<String> declared = new ArrayList<>();

        root.path("paths").fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields()
                .forEachRemaining(methodEntry -> {
                    if (!List.of("get", "post", "put", "delete", "patch").contains(methodEntry.getKey())) {
                        return;
                    }
                    JsonNode security = methodEntry.getValue().path("security");
                    // 显式声明成空数组 = "这个接口不需要认证"
                    if (security.isArray() && security.isEmpty()) {
                        declared.add(methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey());
                    }
                }));

        assertEquals(PUBLIC_OPERATIONS.stream().sorted().toList(),
                declared.stream().sorted().toList(),
                "显式声明「不需要认证」的接口与预期不符。"
                        + "⚠️ 注意：全局默认是所有接口都要 Bearer，"
                        + "所以公开接口必须用 @Operation(security = {}) 单独覆盖 ——"
                        + "否则文档上它们看起来也要 token，调用方会白折腾。");
    }

    @Test
    @DisplayName("每个业务域都必须出现在文档里 —— 防止某个 Controller 整个没被扫到")
    void everyDomainMustAppear() throws Exception {
        List<String> paths = new ArrayList<>();
        fetchApiDocs().path("paths").fieldNames().forEachRemaining(paths::add);

        for (String prefix : REQUIRED_PATH_PREFIXES) {
            assertTrue(paths.stream().anyMatch(p -> p.startsWith(prefix)),
                    "文档里没有 " + prefix + " 下的任何接口，实际路径：" + paths);
        }
    }
}
