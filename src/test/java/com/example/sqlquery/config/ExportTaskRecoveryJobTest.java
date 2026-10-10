package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出任务补偿任务的行为。
 *
 * <p>守的失败模式：导出链路跨了「数据库 → MQ → 消费者」三段，
 * 每段失败都会留下一个<b>不会自己变的状态</b> —— 前端永远转圈，
 * 而且没有任何东西会来纠正它（重复投递被幂等检查挡掉）。
 *
 * <p>这里固定两件事：
 * <ol>
 *   <li><b>两种卡死都要扫</b>：卡在 PENDING（消息没投出去）
 *       和卡在 RUNNING（消费者没能把结果写回去）；
 *   <li><b>必须用条件更新</b>："查出来 → 改 → 写回"中间有时间窗，
 *       任务可能已经被正常完成了，无条件写回会把 SUCCESS 覆盖成 FAILED。
 * </ol>
 */
class ExportTaskRecoveryJobTest {

    private ExportTaskMapper exportTaskMapper;
    private SqlPlatformMetrics metrics;
    private ExportTaskRecoveryJob job;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExportTask.class);

        exportTaskMapper = Mockito.mock(ExportTaskMapper.class);
        metrics = Mockito.mock(SqlPlatformMetrics.class);
        job = new ExportTaskRecoveryJob(exportTaskMapper, metrics);
        ReflectionTestUtils.setField(job, "stuckRunningMinutes", 10);
    }

    private ExportTask stuckTask(Long id, String status) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.now().minusHours(1));
        return task;
    }

    @Test
    @DisplayName("⭐ 卡死判定必须把时间列为 NULL 的记录也收进来（NULL < 任何值都不成立）")
    void stuckCheckMustAlsoCatchNullTimeColumn() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of());

        job.failStuckTasks();

        ArgumentCaptor<Wrapper<ExportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(exportTaskMapper, Mockito.atLeastOnce()).selectList(captor.capture());
        String where = captor.getAllValues().get(0).getSqlSegment();

        // 少了 IS NULL，一条时间列为 NULL 的记录会**永久停在中间态**：
        // 既不会被重投也不会被判失败，而且零信号
        //（migrate_002 / migrate_004 的存在证明这类事故真发生过）。
        assertTrue(where.contains("IS NULL"), "判定条件必须覆盖 NULL，实际：" + where);

        // ⚠️ 而且 OR 必须被 and(...) 包成括号组 —— 否则 SQL 的 AND 优先级会让它变成
        //    「(状态对 且 超时) 或 (时间为空)」，把别的状态的 NULL 行也捞进来。
        assertTrue(where.contains("("), "OR 条件必须成组，实际：" + where);
    }

    @Test
    @DisplayName("PENDING 和 RUNNING 两种卡死都要被扫到")
    void shouldSweepBothPendingAndRunning() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(stuckTask(1L, ExportStatus.RUNNING)),
                        List.of(stuckTask(2L, ExportStatus.PENDING)));
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(1);

        job.failStuckTasks();

        // 两次查询、两次更新：少任何一次都意味着有一类卡死没人管
        Mockito.verify(exportTaskMapper, Mockito.times(2))
                .selectList(ArgumentMatchers.<Wrapper<ExportTask>>any());
        Mockito.verify(exportTaskMapper, Mockito.times(2))
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any());
    }

    @Test
    @DisplayName("更新语句里必须同时改状态、失败原因和完成时间")
    void updateShouldSetStatusReasonAndFinishedAt() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(stuckTask(1L, ExportStatus.RUNNING)), List.of());
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(1);

        job.failStuckTasks();

        ArgumentCaptor<Wrapper<ExportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(exportTaskMapper)
                .update(ArgumentMatchers.isNull(), captor.capture());

        String setClause = ((LambdaUpdateWrapper<ExportTask>) captor.getValue()).getSqlSet();
        assertTrue(setClause.contains("status"), "必须把状态改成 FAILED，实际 SET：" + setClause);
        assertTrue(setClause.contains("error_message"), "必须写明失败原因，否则用户只看到一个失败图标");
        assertTrue(setClause.contains("finished_at"), "必须写完成时间，否则时长统计永远为空");
    }

    @Test
    @DisplayName("⭐ RUNNING 用 started_at 判定、PENDING 用 created_at —— 前者不能把排队时间算进去")
    void runningShouldUseStartedAtWhilePendingUsesCreatedAt() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(), List.of());

        job.failStuckTasks();

        ArgumentCaptor<Wrapper<ExportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(exportTaskMapper, Mockito.times(2)).selectList(captor.capture());

        String runningWhere = ((LambdaQueryWrapper<ExportTask>) captor.getAllValues().get(0)).getSqlSegment();
        String pendingWhere = ((LambdaQueryWrapper<ExportTask>) captor.getAllValues().get(1)).getSqlSegment();

        // RUNNING 关心的是"**执行**了多久"。用 created_at 会把排队时间也算进去 ——
        // 队列积压时「排队 6 分钟 + 执行 5 分钟」的正常任务会被判成卡死，
        // 补偿任务标 FAILED，消费者随后又写回 SUCCESS，用户看到自相矛盾的状态。
        assertTrue(runningWhere.contains("started_at"),
                "RUNNING 的判定必须基于认领时刻（started_at），实际：" + runningWhere);
        assertFalse(runningWhere.contains("created_at"),
                "RUNNING 的判定不能基于创建时刻（含排队时间），实际：" + runningWhere);

        // PENDING 则是另一回事：那时候**还没有** started_at，只能看创建时刻 ——
        // 关心的是"提交之后多久还没被人认领"
        assertTrue(pendingWhere.contains("created_at"),
                "PENDING 只能基于创建时刻，实际：" + pendingWhere);
    }

    @Test
    @DisplayName("没有卡死任务时不应该产生任何写操作")
    void shouldNotUpdateWhenNothingStuck() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of());

        job.failStuckTasks();

        Mockito.verify(exportTaskMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any());
    }

    @Test
    @DisplayName("条件更新影响 0 行说明任务已被完成，不能把它改坏")
    void shouldTolerateTaskCompletedInTheMeantime() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(stuckTask(1L, ExportStatus.RUNNING)), List.of());
        // 影响 0 行 = 在"查出来"和"写回去"之间，任务已经被消费者正常完成了
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(0);

        job.failStuckTasks();

        // 不能抛异常：这是正常的并发场景，不是错误
        Mockito.verify(exportTaskMapper)
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.<Wrapper<ExportTask>>any());
    }
}
