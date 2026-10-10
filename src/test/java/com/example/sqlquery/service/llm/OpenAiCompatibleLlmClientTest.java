package com.example.sqlquery.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型返回值的剥离与规整。
 *
 * <h2>为什么要单独测这一段看起来"没技术含量"的字符串处理</h2>
 *
 * <p>因为它是<b>用户会直接看到后果</b>的那一环：模型十次有八次会把 SQL 包在
 * markdown 代码围栏里，围栏没剥干净，用户收到的就是一句
 * 「SQL语法错误：Encountered unexpected token」——
 * 而他什么都没写错，是系统没处理干净。
 *
 * <p>这类"看起来无关紧要"的适配层，恰恰是最容易在生产上出丑的地方。
 */
class OpenAiCompatibleLlmClientTest {

    private static final String FENCE = "```";

    @Test
    @DisplayName("带语言标记的代码块")
    void shouldStripFenceWithLanguageTag() {
        assertEquals("update t_user set x = 1 where id = 1",
                OpenAiCompatibleLlmClient.stripCodeFence(
                        FENCE + "sql\nupdate t_user set x = 1 where id = 1\n" + FENCE));
    }

    @Test
    @DisplayName("不带语言标记的代码块")
    void shouldStripFenceWithoutLanguageTag() {
        assertEquals("select 1",
                OpenAiCompatibleLlmClient.stripCodeFence(FENCE + "\nselect 1\n" + FENCE));
    }

    @Test
    @DisplayName("没有围栏时原样返回，且去掉首尾空白")
    void shouldReturnPlainTextAsIs() {
        assertEquals("select 1",
                OpenAiCompatibleLlmClient.stripCodeFence("  select 1  "));
    }

    @Test
    @DisplayName("围栏没闭合时也要尽量剥干净")
    void shouldHandleUnclosedFence() {
        assertEquals("select 1",
                OpenAiCompatibleLlmClient.stripCodeFence(FENCE + "sql\nselect 1"));
    }

    @Test
    @DisplayName("null 返回空串，不能抛 NPE")
    void shouldTolerateNull() {
        assertEquals("", OpenAiCompatibleLlmClient.stripCodeFence(null));
    }

    @Test
    @DisplayName("围栏里前面的空行不影响结果")
    void shouldTrimBlankLines() {
        assertEquals("select 1",
                OpenAiCompatibleLlmClient.stripCodeFence(FENCE + "sql\n\nselect 1\n\n" + FENCE));
    }
}
