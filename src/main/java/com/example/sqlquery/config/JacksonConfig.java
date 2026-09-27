package com.example.sqlquery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * 给 Redis 缓存用的 ObjectMapper。
     *
     * <p>⚠️ 必须显式注册 {@link JavaTimeModule}：\`jackson-databind\` 本身**不含** java.time 支持，
     * 而 {@link com.example.sqlquery.entity.DbSource} 上有 {@code LocalDateTime} 字段。
     * 直接用 \`new ObjectMapper()\` 会抛 \`InvalidDefinitionException\`，
     * 而缓存写入是「失败不影响主流程」的静默降级 —— 结果是**缓存永远写不进去，且不报错**。
     *
     * <p>另外关掉 {@code WRITE_DATES_AS_TIMESTAMPS}，让时间序列化成 ISO-8601 字符串而不是数组，
     * 便于用 redis-cli 直接查看缓存内容。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
