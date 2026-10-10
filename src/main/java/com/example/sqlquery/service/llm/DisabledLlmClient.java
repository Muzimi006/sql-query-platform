package com.example.sqlquery.service.llm;

import com.example.sqlquery.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 未启用自然语言生成时的实现。
 *
 * <h2>为什么要有一个"什么都不做"的实现，而不是让 Bean 不存在</h2>
 *
 * <p>如果没配密钥时干脆不注册 {@link LlmClient}，那么所有注入它的类都会
 * <b>启动失败</b> —— 也就是"平台必须配了 API Key 才能跑起来"。
 * 这是个错误的默认值：绝大多数部署根本不需要这个功能。
 *
 * <p>换成"一个明确拒绝的实现"之后：
 * <ul>
 *   <li>应用照常启动，其它功能完全不受影响；</li>
 *   <li>真去点那个入口时，收到的是一句<b>能照着做</b>的提示，
 *       而不是 500 加一段栈；</li>
 *   <li>{@link #available()} 返回 false，前端可以把入口置灰。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "platform.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledLlmClient implements LlmClient {

    public DisabledLlmClient() {
        log.info("自然语言生成 SQL 未启用（platform.llm.enabled=false）。"
                + "需要时配置 platform.llm.enabled / base-url / api-key 后重启即可。");
    }

    @Override
    public String generateSql(String systemPrompt, String userPrompt) {
        // 消息里直接给出配置项名字：这类"功能没开"的提示，
        // 最有用的是告诉人**改哪个开关**，而不是"暂不支持"
        throw new BusinessException("自然语言生成 SQL 功能未启用。"
                + "请在配置中开启 platform.llm.enabled 并填入 platform.llm.api-key（建议用环境变量注入）");
    }

    @Override
    public boolean available() {
        return false;
    }
}
