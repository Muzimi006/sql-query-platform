package com.example.sqlquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型（自然语言转 SQL）的配置。
 *
 * <h2>默认是关的，这不是偷懒</h2>
 *
 * <p>{@code enabled} 默认 {@code false}。理由是：
 * <ol>
 *   <li><b>没有密钥就不能启动</b>是个很糟的默认值。把"接大模型"变成硬依赖，
 *       等于让整个平台在一台没配 API Key 的机器上起不来；</li>
 *   <li><b>发出去的是表结构</b>。让应用在无人知情的情况下把库表清单发给外部服务，
 *       是个真实的<b>数据出境</b>问题 —— 这类开关必须由部署方显式打开。</li>
 * </ol>
 *
 * <p>关闭时的行为是"这个功能明确不可用"（{@link com.example.sqlquery.service.llm.DisabledLlmClient}），
 * 而不是"调用时报一个看不懂的空指针"。
 *
 * <h2>接口形状按 OpenAI 兼容协议写</h2>
 *
 * <p>DeepSeek、通义、Kimi、本地 Ollama / vLLM 都提供 OpenAI 兼容的
 * {@code /chat/completions}。按这个协议写一次，换模型只需要改 {@code base-url} 和 {@code model}，
 * 不用动代码 —— 这一点在本项目尤其重要：<b>文旅 / 文物这类客户多半会要求模型私有化部署</b>，
 * 到时候换的就是一个 base-url。
 */
@Component
@ConfigurationProperties(prefix = "platform.llm")
public class LlmProperties {

    /** 是否启用自然语言生成 SQL。默认关闭，理由见类注释。 */
    private boolean enabled = false;

    /** OpenAI 兼容的接口地址（不含 /chat/completions）。 */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key。<b>只从环境变量注入</b>，不要写进配置文件、更不要提交进仓库。 */
    private String apiKey = "";

    private String model = "deepseek-chat";

    /**
     * 调用超时（秒）。
     *
     * <p>必须有：这是一次<b>同步的外部 HTTP 调用</b>，占着 Web 线程。
     * 没有超时的话，上游卡住会把 Tomcat 线程池慢慢吃干，
     * 最后表现成"整个平台都打不开了"—— 而根因在别人家的服务上。
     */
    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
