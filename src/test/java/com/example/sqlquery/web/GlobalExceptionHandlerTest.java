package com.example.sqlquery.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.example.sqlquery.exception.BusinessException;

/**
 * {@link GlobalExceptionHandler} 的回归测试。
 *
 * <p><b>它守的是一个非常具体的失败模式：某种客户端错误掉进了兜底分支，
 * 被报成「系统异常」。</b> 这类 bug 不影响功能、不产生异常日志，
 * 只是把"你自己参数写错了"伪装成"服务端炸了"，所以必须用自动化测试钉住 ——
 * 靠人肉点接口是发现不了"少写一个 @ExceptionHandler"的。
 *
 * <p>用 standalone MockMvc + 一个只为触发异常而存在的探针控制器，
 * <b>不启动 Spring 上下文</b>，因此不依赖 MySQL / Redis / RabbitMQ，可以进 CI 的单元测试层。
 *
 * <p>每条断言都是"两边都断言"：既要求返回了<b>正确的那句话</b>，
 * 也要求它<b>不是</b>「系统异常」。只断言前者的话，兜底分支恰好返回同样文案时测试仍然会绿。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    /** 真实 metrics（SimpleMeterRegistry）—— 要断言"系统异常被计数了"。 */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(new SqlPlatformMetrics(registry)))
                .build();
    }

    @Test
    @DisplayName("路径变量类型不对：必须提示参数格式，不能报系统异常")
    void typeMismatchShouldNotFallThroughToGenericHandler() throws Exception {
        mockMvc.perform(get("/probe/type/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("参数格式错误")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("id")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not("系统异常")));
    }

    @Test
    @DisplayName("请求体不是合法 JSON：必须提示请求体格式错误")
    void malformedJsonBodyShouldNotFallThroughToGenericHandler() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("请求体格式错误")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not("系统异常")));
    }

    @Test
    @DisplayName("缺少必填请求参数：必须点名是哪个参数")
    void missingRequestParamShouldNotFallThroughToGenericHandler() throws Exception {
        mockMvc.perform(get("/probe/param"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少请求参数")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not("系统异常")));
    }

    @Test
    @DisplayName("请求方法不对：必须提示方法不支持")
    void methodNotSupportedShouldNotFallThroughToGenericHandler() throws Exception {
        mockMvc.perform(post("/probe/method"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("请求方法不支持")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not("系统异常")));
    }

    @Test
    @DisplayName("唯一约束冲突要按约束名给提示，不能一律说成用户名已存在")
    void duplicateKeyShouldBeTranslatedByConstraintName() throws Exception {
        mockMvc.perform(get("/probe/duplicate/datasource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("数据源名称已存在"));

        mockMvc.perform(get("/probe/duplicate/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户名已存在"));

        // 认不出来的约束给通用提示，而不是猜一个具体但可能错误的原因
        mockMvc.perform(get("/probe/duplicate/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("数据已存在，请勿重复提交"));
    }

    @Test
    @DisplayName("真正没预料到的异常才允许报系统异常，⭐ 而且必须被计数")
    void unexpectedExceptionShouldStillBeGeneric() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("系统异常"));

        // ⭐ 这个项目的错误响应一律是 HTTP 200 + body 里的 code，
        //    所以"服务端炸了"在 http_server_requests{status=...} 上**看不出来** ——
        //    按 HTTP 状态算错误率的告警对本项目是瞎的，计数器是那条链路上唯一的信号。
        assertEquals(1.0, registry.get(SqlPlatformMetrics.SYSTEM_ERROR).counter().count(),
                "系统异常必须留下可告警的计数器");
    }

    @Test
    @DisplayName("业务异常原样透传，不能被兜底分支改写")
    void businessExceptionShouldPassThrough() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("数据源已停用"));
    }

    /** 只为触发各类异常而存在的探针控制器，不属于生产代码。 */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/type/{id}")
        public String type(@PathVariable Long id) {
            return "ok" + id;
        }

        @PostMapping("/body")
        public String body(@RequestBody ProbeBody body) {
            return "ok";
        }

        @GetMapping("/param")
        public String param(@RequestParam String name) {
            return "ok" + name;
        }

        @GetMapping("/method")
        public String method() {
            return "ok";
        }

        @GetMapping("/duplicate/{kind}")
        public String duplicate(@PathVariable String kind) {
            String detail = switch (kind) {
                case "datasource" -> "Duplicate entry 'a' for key 'data_source.uk_user_id_name'";
                case "user" -> "Duplicate entry 'a' for key 'user.username'";
                default -> "Duplicate entry 'a' for key 'some_other_uk'";
            };
            throw new DuplicateKeyException(detail);
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("模拟未预料的异常");
        }

        @GetMapping("/business")
        public String business() {
            throw new BusinessException("数据源已停用");
        }
    }

    /** 请求体类型，字段类型故意与 JSON 对不上时用于触发解析失败。 */
    static class ProbeBody {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
