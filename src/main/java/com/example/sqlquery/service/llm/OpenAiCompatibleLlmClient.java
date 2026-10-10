package com.example.sqlquery.service.llm;

import com.example.sqlquery.config.LlmProperties;
import com.example.sqlquery.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 OpenAI 兼容协议调用的模型客户端。
 *
 * <h2>为什么是"OpenAI 兼容"而不是"DeepSeek 专用"</h2>
 *
 * <p>DeepSeek、通义千问、Kimi、智谱、本地 Ollama、vLLM 都提供同一套
 * {@code POST /chat/completions} 协议。按这个协议写一次，
 * 换模型只改 {@code base-url} 和 {@code model} 两个配置项。
 *
 * <p>这一点对本项目的目标客户不是"顺手"，而是<b>必需</b>：
 * 文旅、文物这类客户对"数据出不出内网"很敏感，几乎一定会要求私有化部署。
 * 到那时的改动应该只是把 base-url 指向内网地址。
 *
 * <h2>三件必须做对的事</h2>
 *
 * <ol>
 *   <li><b>必须有超时。</b>这是一次同步的外部 HTTP 调用，占着 Web 线程。
 *       没有超时的话，上游卡住会把 Tomcat 线程池吃干，
 *       现象是"整个平台打不开"，而根因在别人家的服务上。</li>
 *   <li><b>错误信息不能把 API Key 带出去。</b>见 {@link #brief}。</li>
 *   <li><b>剥掉 markdown 代码围栏。</b>模型十次有八次会回
 *       {@code ```sql ... ```}，把围栏原样丢给解析器只会得到一句语法错误。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "platform.llm", name = "enabled", havingValue = "true")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();

        log.info("自然语言生成 SQL 已启用：baseUrl={}, model={}", properties.getBaseUrl(), properties.getModel());
    }

    @Override
    public String generateSql(String systemPrompt, String userPrompt) {
        // 用 LinkedHashMap 而不是 Map.of：需要显式的 Map<String, Object> 类型，
        // 而且 Map.of 是不可变的、遇到 null 值直接抛 NPE
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        // temperature = 0：这个场景要的是"每次都生成同一条 SQL"，
        // 不是"有创意"。同一条需求两次生成不同的 SQL，会让"审批人看到的那条"
        // 和"用户以为的那条"对不上，而这正是审批系统最怕的事
        body.put("temperature", 0);

        String raw;
        try {
            raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // ⚠️ 只取状态码和一小段响应体，绝不把请求头带出去 ——
            //    请求头里有 Authorization: Bearer <API Key>
            log.error("调用大模型失败：status={}", e.getStatusCode(), e);
            throw new BusinessException("大模型调用失败（HTTP " + e.getStatusCode().value() + "）："
                    + brief(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.error("调用大模型失败", e);
            throw new BusinessException("大模型调用失败：" + brief(e.getMessage()));
        }

        return stripCodeFence(extractContent(raw));
    }

    /** 从 OpenAI 兼容的响应体里取出 choices[0].message.content。 */
    private String extractContent(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new BusinessException("大模型返回内容为空，请重试或换一种描述");
            }
            return content.asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("大模型返回的内容无法解析：" + brief(e.getMessage()));
        }
    }

    /**
     * 剥掉 markdown 代码围栏。
     *
     * <p>模型几乎总会把 SQL 包在 {@code ```sql ... ```} 里。
     * 这段围栏对解析器来说是纯粹的语法噪音，必须在这里处理掉 ——
     * 让它流到 {@code SqlValidateUtil} 里，用户收到的会是
     * 「SQL语法错误」，而他根本没写错什么，是系统没剥干净。
     *
     * <p>包级可见是为了让单元测试能直接打它，不需要真的起一个 HTTP 服务。
     */
    static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }

        return trimmed.trim();
    }

    private String brief(String message) {
        if (message == null) {
            return "未知原因";
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    @Override
    public boolean available() {
        return true;
    }
}
