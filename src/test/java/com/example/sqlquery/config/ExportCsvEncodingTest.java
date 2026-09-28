package com.example.sqlquery.config;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.util.ConnectionManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出 CSV 的编码回归测试。
 *
 * <p>背景：原先用的是 {@code new FileWriter(file)}，它走<b>平台默认字符集</b>——
 * Windows 上是 GBK、Linux 上是 UTF-8。这类 bug 单机测试永远发现不了
 * （本机写和读都是同一个编码，看着一切正常），到了 Linux 或者用 Excel 打开中文就乱码。
 *
 * <p>本用例把 Connection / Statement / ResultSet 全部 mock 掉，
 * 只验证一件事：<b>产出的字节里，BOM 在不在、中文能不能按 UTF-8 还原</b>。
 */
class ExportCsvEncodingTest {

    @Test
    void csvShouldBeUtf8WithBomAndKeepChinese() throws Exception {
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);

        when(connectionManager.getConnection(any(DbSource.class))).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("名称");
        when(metaData.getColumnLabel(2)).thenReturn("备注");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("中文内容");
        when(resultSet.getObject(2)).thenReturn("带,逗号");

        ExportConsumer consumer = new ExportConsumer(null, null, null, connectionManager);

        File file = File.createTempFile("export-encoding-", ".csv");
        try {
            long rowCount = consumer.generateCsv(new DbSource(), "select 1", file);
            assertEquals(1, rowCount);

            byte[] bytes = Files.readAllBytes(file.toPath());
            assertTrue(bytes.length > 3, "导出的文件不应为空");

            // ① 开头必须是 UTF-8 BOM，否则 Excel 打开中文仍然乱码
            assertEquals((byte) 0xEF, bytes[0], "缺少 UTF-8 BOM（第 1 字节）");
            assertEquals((byte) 0xBB, bytes[1], "缺少 UTF-8 BOM（第 2 字节）");
            assertEquals((byte) 0xBF, bytes[2], "缺少 UTF-8 BOM（第 3 字节）");

            // ② 跳过 BOM 后按 UTF-8 解码，中文必须原样还原
            String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            assertTrue(content.contains("名称"), "表头中文丢失，实际内容：" + content);
            assertTrue(content.contains("中文内容"), "数据中文丢失，实际内容：" + content);

            // ③ 顺带确认 CSV 转义没被这次改动破坏
            assertTrue(content.contains("\"带,逗号\""), "含逗号的字段应被双引号包裹，实际：" + content);
        } finally {
            file.delete();
        }
    }
}
