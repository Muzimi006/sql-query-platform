package com.example.sqlquery.job;

import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.mapper.QueryHistoryMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留策略：一张只会涨的表，迟早会以「磁盘满」的形式变成线上故障。
 *
 * <p>这个类最要紧的性质不是「能删」，而是三条自我保护：
 * <ol>
 *   <li><b>默认关闭</b> —— 删除不可逆，保留多久是业务决定，应用不该替人决定；</li>
 *   <li><b>保留天数小于等于 0 时一项都不删</b> —— 那时 cutoff = now，等于删光整张表；</li>
 *   <li><b>分批 + 逐表捕获异常</b> —— 一次删几百万行会持锁、抢连接，
 *       而一张表失败不该让另外两张也停下。</li>
 * </ol>
 */
class DataRetentionJobTest {

    private static final int BATCH = 1000;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);
    private ExportTaskMapper exportTaskMapper;
    private AuditLogMapper auditLogMapper;
    private QueryHistoryMapper queryHistoryMapper;

    /**
     * ⚠️ 这一步是必须的，而且踩过一次：`LambdaQueryWrapper.select(ExportTask::getId)` 会**立刻**
     * 把方法引用解析成列名，而列名来自 MyBatis-Plus 的 TableInfo 缓存 ——
     * 那个缓存在真实运行期由 mapper 扫描填充，**纯单元测试里没有 Spring 上下文，缓存是空的**，
     * 于是包裹在 lambda 里的那句会抛 "can not find lambda cache for this entity"，
     * 而 purgeTarget 把它catch 成"查询失败" —— 表现为 6 条用例全红、且 mock 上"零交互"，
     * 很容易误判成生产代码有问题。这里手工把三张表的元数据初始化进去。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExportTask.class);
        TableInfoHelper.initTableInfo(assistant, AuditLog.class);
        TableInfoHelper.initTableInfo(assistant, QueryHistory.class);
    }

    @BeforeEach
    void setUp() {
        exportTaskMapper = Mockito.mock(ExportTaskMapper.class);
        auditLogMapper = Mockito.mock(AuditLogMapper.class);
        queryHistoryMapper = Mockito.mock(QueryHistoryMapper.class);
    }

    private DataRetentionJob job(boolean enabled, int exportDays, int auditDays, int historyDays) {
        return new DataRetentionJob(exportTaskMapper, auditLogMapper, queryHistoryMapper, metrics,
                enabled, BATCH, 3, exportDays, auditDays, historyDays);
    }

    private static List<ExportTask> exportTasks(int from, int count) {
        List<ExportTask> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ExportTask task = new ExportTask();
            task.setId((long) from + i);
            list.add(task);
        }
        return list;
    }

    private double deleted(String target) {
        var counter = registry.find(SqlPlatformMetrics.RETENTION_DELETED).tag("target", target).counter();
        return counter == null ? 0 : counter.count();
    }

    private double failed(String target) {
        var counter = registry.find(SqlPlatformMetrics.RETENTION_FAILED).tag("target", target).counter();
        return counter == null ? 0 : counter.count();
    }

    private void nothingToDelete() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        Mockito.when(auditLogMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        Mockito.when(queryHistoryMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("⭐ 默认关闭时一行都不删：删除不可逆，应用不替人做这个决定")
    void disabledShouldDeleteNothing() {
        job(false, 30, 180, 90).purge();
        Mockito.verifyNoInteractions(exportTaskMapper);
        Mockito.verifyNoInteractions(auditLogMapper);
        Mockito.verifyNoInteractions(queryHistoryMapper);
        assertEquals(0.0, deleted("export_task"));
    }

    @Test
    @DisplayName("⭐ 保留天数小于等于 0 的表必须被跳过：否则 cutoff = now 会把整张表删光")
    void nonPositiveRetentionDaysShouldSkipThatTable() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(exportTasks(1, 5));
        job(true, 0, 180, 90).purge();
        Mockito.verify(exportTaskMapper, Mockito.never())
                .deleteBatchIds(ArgumentMatchers.any());
        assertEquals(0.0, deleted("export_task"), "0 天的表一行都不该被删");
        Mockito.verify(auditLogMapper).selectList(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("开启后按批删除，并且把删掉的行数记进指标")
    void enabledShouldDeleteAndCount() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(exportTasks(1, 3), Collections.emptyList());
        Mockito.when(exportTaskMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(3);
        Mockito.when(auditLogMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        Mockito.when(queryHistoryMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        job(true, 30, 180, 90).purge();
        Mockito.verify(exportTaskMapper, Mockito.times(1)).selectList(ArgumentMatchers.any());
        Mockito.verify(exportTaskMapper).deleteBatchIds(List.of(1L, 2L, 3L));
        assertEquals(3.0, deleted("export_task"),
                "删除必须计数 —— 删除不可逆，事后唯一能查的就是这个数字");
    }

    @Test
    @DisplayName("满一批时会继续下一批，但受单轮批次上限约束（不能把平台库占满）")
    void shouldStopAtMaxBatchesPerRun() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(exportTasks(1, BATCH));
        Mockito.when(exportTaskMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(BATCH);
        Mockito.when(auditLogMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        Mockito.when(queryHistoryMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        job(true, 30, 180, 90).purge();
        Mockito.verify(exportTaskMapper, Mockito.times(3)).selectList(ArgumentMatchers.any());
        assertEquals(3.0 * BATCH, deleted("export_task"));
    }

    @Test
    @DisplayName("一张表失败不影响另外两张（并记 failed 计数）")
    void failureInOneTableShouldNotStopOthers() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("平台库连接被占满"));
        Mockito.when(auditLogMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(new AuditLog()));
        Mockito.when(auditLogMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(1);
        job(true, 30, 180, 90).purge();
        assertEquals(1.0, failed("export_task"));
        assertEquals(0.0, failed("audit_log"));
        Mockito.verify(auditLogMapper).deleteBatchIds(ArgumentMatchers.any());
        assertEquals(1.0, deleted("audit_log"));
    }

    @Test
    @DisplayName("删除抛异常时停止这一张表，但已经删掉的行数要照实记")
    void deleteFailureShouldRecordWhatWasDeleted() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(exportTasks(1, BATCH), exportTasks(2001, BATCH));
        Mockito.when(exportTaskMapper.deleteBatchIds(ArgumentMatchers.any()))
                .thenReturn(BATCH, 0)
                .thenThrow(new RuntimeException("锁等待超时"));
        job(true, 30, 180, 90).purge();
        assertEquals(1.0, failed("export_task"));
        assertTrue(deleted("export_task") >= BATCH, "第一批已经删掉了，计数必须反映事实");
    }

    @Test
    @DisplayName("筛选条件必须是 created_at + 排序 + 批次上限")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cutoffShouldBeDerivedFromConfiguredDays() {
        nothingToDelete();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(exportTasks(1, 1));
        Mockito.when(exportTaskMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(1);
        Mockito.when(auditLogMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        Mockito.when(queryHistoryMapper.deleteBatchIds(ArgumentMatchers.any())).thenReturn(0);
        job(true, 7, 7, 7).purge();
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        Mockito.verify(exportTaskMapper).selectList(captor.capture());
        String sql = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper) captor.getValue()).getSqlSegment();
        assertTrue(sql.contains("created_at"), "筛的必须是 created_at，实际：" + sql);
        assertTrue(sql.contains("ORDER BY"), "必须按主键升序、先删最老的，实际：" + sql);
        assertTrue(sql.contains("LIMIT"), "必须有批次上限，实际：" + sql);
    }

}

