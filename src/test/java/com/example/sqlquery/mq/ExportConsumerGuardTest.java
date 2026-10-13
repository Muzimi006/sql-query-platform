package com.example.sqlquery.mq;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.infra.ConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 导出链路的**两个执行保护**：查询超时与行数上限。
 *
 * <h2>为什么要专门给这两样写测试</h2>
 *
 * <p>查询链路从第一天就有 {@code setQueryTimeout(30)} 和 {@code setMaxRows(1001)}，
 * 而**导出链路最初两个都没有** —— 它用的是<b>同一个连接池</b>（上限 5），
 * 所以一条挂住的慢 SQL 或者一次全表扫描，影响的不只是这一个导出任务。
 *
 * <p>这个疏漏之所以能存在很久，是因为：**它不会以"错误"的形式出现**。
 * 没有超时，接口照样返回成功；没有行数上限，小表导出照样正常。
 * 只有等到有人导出一张亿级大表、或者某条 SQL 挂住时才会暴露 ——
 * 那时候表现为"磁盘满了"或者"查询都变慢了"，**离根因很远**。
 *
 * <p>所以这里的第一条用例<b>直接断言"这两个方法被调用了"</b> ——
 * 它守的不是某个具体行为，而是"**这条链路有没有装保护**"这个事实。
 */
class ExportConsumerGuardTest {

    /** 测试里把上限设成很小，方便构造"刚好"和"超一行"的边界。 */
    private static final int TEST_MAX_ROWS = 3;
    private static final int TEST_TIMEOUT_SECONDS = 300;

    private ConnectionManager connectionManager;
    private ExportConsumer consumer;

    @BeforeEach
    void setUp() {
        connectionManager = Mockito.mock(ConnectionManager.class);
        consumer = new ExportConsumer(null, null, connectionManager, null);
        ReflectionTestUtils.setField(consumer, "maxRows", TEST_MAX_ROWS);
        ReflectionTestUtils.setField(consumer, "queryTimeoutSeconds", TEST_TIMEOUT_SECONDS);
    }

    /**
     * 造一个"会返回 n 行"的假数据库。
     *
     * <p>返回的 Statement 会被用例拿去断言 —— 这是重点：
     * 我们要验的正是"有没有在它上面装保护"。
     */
    private Statement stubDatabase(int rowCount) throws Exception {
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);

        Mockito.when(connectionManager.getConnection(any(DbSource.class))).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(anyString())).thenReturn(resultSet);

        Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnLabel(1)).thenReturn("c");
        Mockito.when(resultSet.getObject(1)).thenReturn("x");

        // next() 前 rowCount 次返回 true，之后返回 false
        AtomicInteger seen = new AtomicInteger();
        Mockito.when(resultSet.next()).thenAnswer(invocation -> seen.getAndIncrement() < rowCount);

        return statement;
    }

    private File tempCsv() throws Exception {
        File file = File.createTempFile("export-guard-", ".csv");
        file.deleteOnExit();
        return file;
    }

    @Test
    @DisplayName("★ 导出必须设查询超时和行数上限 —— 这正是原来漏掉的两样")
    void generateCsvMustConfigureTimeoutAndRowLimit() throws Exception {
        Statement statement = stubDatabase(1);

        consumer.generateCsv(new DbSource(), "select 1", tempCsv());

        Mockito.verify(statement).setQueryTimeout(TEST_TIMEOUT_SECONDS);

        // 多读一行：用来区分"刚好等于上限"和"被截断了还有更多"
        Mockito.verify(statement).setMaxRows(TEST_MAX_ROWS + 1);
    }

    @Test
    @DisplayName("超过行数上限：整个任务失败，而不是截断后谎报成功")
    void exceedingRowLimitShouldFailTheWholeTask() throws Exception {
        stubDatabase(TEST_MAX_ROWS + 1);

        BusinessException e = assertThrows(BusinessException.class,
                () -> consumer.generateCsv(new DbSource(), "select 1", tempCsv()));

        // 报错要能指导用户下一步做什么，而不只是"导出失败"
        assertTrue(e.getMessage().contains(String.valueOf(TEST_MAX_ROWS)),
                "报错里应带上限值，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("缩小查询范围"),
                "报错里应告诉用户怎么办，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("刚好等于上限：正常成功（边界不能误伤）")
    void exactlyAtRowLimitShouldSucceed() throws Exception {
        File file = tempCsv();
        stubDatabase(TEST_MAX_ROWS);

        long rows = consumer.generateCsv(new DbSource(), "select 1", file);

        assertEquals(TEST_MAX_ROWS, rows, "刚好等于上限不该被拒");
        assertTrue(file.length() > 0, "文件应该有内容");
    }

    @Test
    @DisplayName("超限时抛的是业务异常 —— 这样 execute() 的 catch 能把它写成 FAILED")
    void rowLimitExceptionShouldBeABusinessException() throws Exception {
        stubDatabase(TEST_MAX_ROWS + 5);

        // 上层 execute() 是 catch (Exception e) → 置 FAILED + 写 error_message。
        // 如果这里抛的是 Error 或者被吞掉，任务就会停在 RUNNING。
        assertThrows(BusinessException.class,
                () -> consumer.generateCsv(new DbSource(), "select 1", tempCsv()));
    }
}
