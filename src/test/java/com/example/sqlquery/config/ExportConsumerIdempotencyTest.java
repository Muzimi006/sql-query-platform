package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.ConnectionManager;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出消费者的<b>幂等性</b>与失败落库行为。
 *
 * <p>守的失败模式：同一个 {@code taskId} 被消费两次。
 * MQ 的投递语义是"至少一次"，Broker 没收到 ack 就会重投，
 * 所以重复消费是<b>正常情况</b>，不是异常。原来的实现只判断"任务存在不存在"，
 * 存在就重跑一遍 —— 会重新执行一次那条 SQL 并覆盖输出文件。
 *
 * <p>现在用"条件更新成功才算认领到"来表达幂等：只有把任务从
 * {@link ExportStatus#PENDING} 改成 {@link ExportStatus#RUNNING} 的那一次调用会返回 1，
 * 其余一律返回 0。这条用例把"返回 0 时必须什么都不做"钉死。
 */
class ExportConsumerIdempotencyTest {

    private ExportTaskMapper exportTaskMapper;
    private DbSourceService dbSourceService;
    private ConnectionManager connectionManager;
    private ExportConsumer consumer;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 依赖 MyBatis-Plus 的 lambda 缓存，而缓存是在 mapper 注册时建立的。
        // 单元测试没有 MyBatis 上下文，需要手工初始化一次。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExportTask.class);

        exportTaskMapper = Mockito.mock(ExportTaskMapper.class);
        dbSourceService = Mockito.mock(DbSourceService.class);
        connectionManager = Mockito.mock(ConnectionManager.class);
        consumer = Mockito.spy(new ExportConsumer(exportTaskMapper, dbSourceService, connectionManager));
    }

    private ExportTask pendingTask(Long id) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setUserId(1L);
        task.setDataSourceId(2L);
        task.setSqlText("select 1");
        task.setStatus(ExportStatus.PENDING);
        return task;
    }

    @Test
    @DisplayName("条件更新影响 1 行 = 认领成功")
    void shouldClaimWhenConditionalUpdateAffectsOneRow() {
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(1);

        assertTrue(consumer.tryClaim(1L));
    }

    @Test
    @DisplayName("条件更新影响 0 行 = 已被认领或已完成，不能重复执行")
    void shouldNotClaimWhenNothingUpdated() {
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(0);

        assertFalse(consumer.tryClaim(1L), "影响行数为 0 时不能认为认领成功");
    }

    @Test
    @DisplayName("重复投递时：消息被 ack 掉，且不会再去查数据源或写文件")
    void duplicateDeliveryShouldAckWithoutDoingWork() throws Exception {
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(0);

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        consumer.handle(1L, channel, 42L);

        // 消息必须被 ack：不 ack 的话 Broker 会一直重投，陷入死循环
        Mockito.verify(channel).basicAck(42L, false);
        Mockito.verify(channel, Mockito.never()).basicNack(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean());
        // 关键：没有真的去干活
        Mockito.verify(exportTaskMapper, Mockito.never()).selectById(Mockito.anyLong());
        Mockito.verify(dbSourceService, Mockito.never())
                .getByIdAndUserId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @DisplayName("执行成功：写回 SUCCESS + 文件路径 + 行数")
    void executeShouldWriteSuccessResult() throws Exception {
        ExportTask task = pendingTask(1L);
        Mockito.when(exportTaskMapper.selectById(1L)).thenReturn(task);
        Mockito.when(dbSourceService.getByIdAndUserId(1L, 2L)).thenReturn(new DbSource());
        Mockito.doReturn(3L).when(consumer)
                .generateCsv(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());

        consumer.execute(1L);

        LambdaUpdateWrapper<ExportTask> wrapper = captureFinishUpdate();
        Map<String, Object> params = wrapper.getParamNameValuePairs();

        assertTrue(params.values().contains(ExportStatus.SUCCESS), "状态必须写回 SUCCESS，实际：" + params.values());
        assertTrue(params.values().contains(3L), "行数必须落库，实际：" + params.values());
        assertTrue(params.values().stream().anyMatch(v -> v instanceof String s && s.endsWith("1.csv")),
                "文件路径必须落库，实际：" + params.values());
        assertTrue(wrapper.getSqlSet().contains("finished_at"), "完成时间必须落库");

        // ⭐ 回写必须带认领令牌：只有"仍然是我认领的 RUNNING"才允许写回
        assertTrue(wrapper.getSqlSegment().contains("status"),
                "回写的 WHERE 必须带认领令牌，实际：" + wrapper.getSqlSegment());
    }

    /** 抓取 finish() 发出的那次回写（条件更新）。 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ExportTask> captureFinishUpdate() {
        ArgumentCaptor<Wrapper<ExportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(exportTaskMapper).update(ArgumentMatchers.isNull(), captor.capture());
        return (LambdaUpdateWrapper<ExportTask>) captor.getValue();
    }

    @Test
    @DisplayName("执行失败：写回 FAILED + 原因，并删掉写了一半的 CSV")
    void executeShouldMarkFailedAndCleanPartialFile() throws Exception {
        ExportTask task = pendingTask(1L);
        Mockito.when(exportTaskMapper.selectById(1L)).thenReturn(task);
        Mockito.when(dbSourceService.getByIdAndUserId(1L, 2L)).thenReturn(new DbSource());
        // 模拟"文件已经建出来、写到一半才失败"（不是"数据源查不到"那种还没开始写的情况）
        Mockito.doAnswer(invocation -> {
            File target = invocation.getArgument(2);
            Files.writeString(target.toPath(), "half written");
            throw new java.sql.SQLException("连接中断");
        }).when(consumer).generateCsv(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());

        consumer.execute(1L);

        Map<String, Object> params = captureFinishUpdate().getParamNameValuePairs();
        assertTrue(params.values().contains(ExportStatus.FAILED), "实际：" + params.values());
        assertTrue(params.values().contains("连接中断"), "实际：" + params.values());

        // 半成品文件必须被清掉，否则磁盘上会慢慢堆一批没人认领的垃圾
        assertFalse(new File("exports", "1.csv").exists(), "失败后不应留下半成品 CSV");
    }

    @Test
    @DisplayName("写库本身失败时异常必须往外抛，好让消息进死信队列")
    void executeShouldPropagateWhenResultCannotBePersisted() throws Exception {
        ExportTask task = pendingTask(1L);
        Mockito.when(exportTaskMapper.selectById(1L)).thenReturn(task);
        Mockito.when(dbSourceService.getByIdAndUserId(1L, 2L)).thenReturn(new DbSource());
        Mockito.doReturn(0L).when(consumer)
                .generateCsv(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("数据库不可用"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> consumer.execute(1L));
    }

    @Test
    @DisplayName("落库失败时：消息 nack 且不重新入队，走死信队列")
    void persistenceFailureShouldNackIntoDeadLetterQueue() throws Exception {
        // 第一次 update 是认领（成功），第二次是回写（失败）
        Mockito.when(exportTaskMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(1)
                .thenThrow(new RuntimeException("数据库不可用"));
        ExportTask task = pendingTask(1L);
        Mockito.when(exportTaskMapper.selectById(1L)).thenReturn(task);
        Mockito.when(dbSourceService.getByIdAndUserId(1L, 2L)).thenReturn(new DbSource());
        Mockito.doReturn(0L).when(consumer)
                .generateCsv(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());

        com.rabbitmq.client.Channel channel = Mockito.mock(com.rabbitmq.client.Channel.class);
        consumer.handle(1L, channel, 42L);

        // requeue 必须是 false：持续性故障时重投会在队列里无限打转
        Mockito.verify(channel).basicNack(42L, false, false);
        Mockito.verify(channel, Mockito.never()).basicAck(Mockito.anyLong(), Mockito.anyBoolean());
    }

    @Test
    @DisplayName("任务已被删除时不应误报：什么都不写")
    void executeShouldDoNothingWhenTaskGone() {
        Mockito.when(exportTaskMapper.selectById(1L)).thenReturn(null);

        consumer.execute(1L);

        Mockito.verify(exportTaskMapper, Mockito.never())
                .update(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }
}
