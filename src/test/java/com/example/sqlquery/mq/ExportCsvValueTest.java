package com.example.sqlquery.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出 CSV 的单元格转换。
 *
 * <p>它守的是两类**静默的错误数据**：
 * <ol>
 *   <li><b>二进制列写成对象地址</b>：{@code byte[].toString()} 是 {@code [B@1a2b3c}，
 *       一份"看起来正常"的 CSV 里全是乱码地址；</li>
 *   <li><b>公式注入</b>：以 {@code =} {@code +} {@code -} {@code @} 开头的单元格，
 *       Excel / WPS 打开时会**当公式执行**（可以外带数据）。</li>
 * </ol>
 */
class ExportCsvValueTest {

    private final ExportConsumer consumer = new ExportConsumer(null, null, null, null);

    @Test
    @DisplayName("⭐ 二进制列必须转 Base64，不能是 [B@1a2b3c 这种对象地址")
    void binaryColumnShouldBeBase64() throws Exception {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        String cell = consumer.csvValue(bytes);

        assertEquals(Base64.getEncoder().encodeToString(bytes), cell);
        assertFalse(cell.contains("[B@"),
                "写成 [B@... 是最坏的一类错误：不报错、不告警，用户拿去用。实际：" + cell);
    }

    @Test
    @DisplayName("NULL 是空串，不是字符串 \"null\"")
    void nullShouldBeEmpty() throws Exception {
        assertEquals("", consumer.csvValue(null));
    }

    @Test
    @DisplayName("普通值原样输出")
    void plainValueShouldPassThrough() throws Exception {
        assertEquals("42", consumer.csvValue(42));
        assertEquals("abc", consumer.csvValue("abc"));
    }

    @Test
    @DisplayName("⭐ 公式注入：以 = + - @ 开头的单元格要前置单引号")
    void formulaInjectionShouldBeNeutralised() {
        // 这一条是真实攻击面：导出结果里如果有人可控的字段以 = 开头，
        // 打开 CSV 就会执行公式（经典的是 =cmd|'/c calc'!A0）
        for (String dangerous : new String[]{"=1+1", "+1", "-1", "@SUM(A1)"}) {
            String escaped = consumer.csvEscape(dangerous);
            assertTrue(escaped.startsWith("'"),
                    "以危险字符开头的单元格必须被前置单引号：" + dangerous + " → " + escaped);
        }
    }

    @Test
    @DisplayName("结构性转义照旧：逗号 / 引号 / 换行要加引号并翻倍")
    void structuralEscapingShouldStillWork() {
        assertEquals("\"a,b\"", consumer.csvEscape("a,b"));
        assertEquals("\"a\"\"b\"", consumer.csvEscape("a\"b"));
        assertEquals("\"a\nb\"", consumer.csvEscape("a\nb"));
        // 普通值不加任何东西
        assertEquals("abc", consumer.csvEscape("abc"));
    }
}