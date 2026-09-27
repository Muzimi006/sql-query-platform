package com.example.sqlquery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：缓存用的 ObjectMapper 必须能序列化 LocalDateTime。
 *
 * <p>背景：\`new ObjectMapper()\` 默认不含 java.time 支持，序列化 \`DbSource\` 会抛异常，
 * 而这个异常被「写缓存失败不影响主流程」的 catch 吞掉了 —— 表现为**缓存静默失效**。
 */
class JacksonConfigTest {

    /** 与 DbSource 同构：带 LocalDateTime 字段的 POJO。 */
    public static class Holder {
        private Long id = 1L;
        private LocalDateTime createdAt = LocalDateTime.of(2026, 9, 25, 10, 30);

        public Long getId() {
            return id;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }
    }

    @Test
    void shouldSerializeAndDeserializeLocalDateTime() {
        ObjectMapper mapper = new JacksonConfig().objectMapper();

        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(new Holder()));

        // 关掉 WRITE_DATES_AS_TIMESTAMPS 后应该是 ISO-8601 字符串，而不是 [2026,9,25,...] 数组
        assertTrue(json.contains("2026-09-25T10:30"), "时间应序列化为 ISO-8601 字符串，实际：" + json);

        Holder back = assertDoesNotThrow(() -> mapper.readValue(json, Holder.class));
        assertEquals(LocalDateTime.of(2026, 9, 25, 10, 30), back.getCreatedAt());
    }
}
