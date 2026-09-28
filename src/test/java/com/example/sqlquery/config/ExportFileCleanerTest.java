package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出文件清理任务的行为。
 *
 * <p>要点：删文件的同时必须把 {@code export_task.file_path} 置空 ——
 * 否则列表接口会一直告诉用户"可以下载"，点下去却报文件不存在。
 *
 * <p>另外注意实现里用的是 {@code update(null, LambdaUpdateWrapper)} 而不是 {@code updateById}：
 * MyBatis-Plus 的 {@code updateById} 默认忽略 null 字段，写 {@code setFilePath(null)} 再调它，
 * 生成的 SQL 根本不会带 {@code file_path}，等于没清。这条用例顺带把这个约束固定下来。
 */
class ExportFileCleanerTest {

    private ExportTaskMapper exportTaskMapper;
    private ExportFileCleaner cleaner;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 依赖 MyBatis-Plus 的 lambda 缓存，而缓存是在 mapper 注册时建立的。
        // 单元测试没有 MyBatis 上下文，需要手工初始化一次，否则会报
        // "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExportTask.class);

        exportTaskMapper = Mockito.mock(ExportTaskMapper.class);
        cleaner = new ExportFileCleaner(exportTaskMapper);
        ReflectionTestUtils.setField(cleaner, "retentionDays", 7);
    }

    private ExportTask taskPointingTo(File file) {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setStatus("SUCCESS");
        task.setFilePath(file.getAbsolutePath());
        task.setCreatedAt(LocalDateTime.now().minusDays(30));
        return task;
    }

    private File tempFile() throws Exception {
        Path path = Files.createTempFile("export-cleaner-", ".csv");
        Files.writeString(path, "a,b\n1,2\n");
        return path.toFile();
    }

    private File missingFile() {
        return new File(System.getProperty("java.io.tmpdir"),
                "not-exist-" + UUID.randomUUID() + ".csv");
    }

    @Test
    void shouldDeleteExpiredFileAndClearPath() throws Exception {
        File file = tempFile();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(taskPointingTo(file)));

        cleaner.cleanExpiredFiles();

        assertFalse(file.exists(), "过期文件应该被删除");
        Mockito.verify(exportTaskMapper).update(
                ArgumentMatchers.isNull(),
                ArgumentMatchers.<Wrapper<ExportTask>>any());
    }

    @Test
    void shouldStillClearPathWhenFileAlreadyMissing() throws Exception {
        File missing = missingFile();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of(taskPointingTo(missing)));

        cleaner.cleanExpiredFiles();

        // 文件早就不在了，但数据库还指着它 —— 同样要把路径清掉
        Mockito.verify(exportTaskMapper).update(
                ArgumentMatchers.isNull(),
                ArgumentMatchers.<Wrapper<ExportTask>>any());
    }

    @Test
    void shouldDoNothingWhenNothingExpired() {
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of());

        cleaner.cleanExpiredFiles();

        Mockito.verify(exportTaskMapper, Mockito.never()).update(
                ArgumentMatchers.<ExportTask>isNull(),
                ArgumentMatchers.<Wrapper<ExportTask>>any());
    }

    @Test
    void shouldKeepFileUntilRetentionWindowPasses() throws Exception {
        // 这条用例守的是「保留窗口」本身：recent 的任务不在查询条件命中的范围里，
        // 所以 mapper 返回空列表时不应该动任何文件
        File file = tempFile();
        Mockito.when(exportTaskMapper.selectList(ArgumentMatchers.<Wrapper<ExportTask>>any()))
                .thenReturn(List.of());

        cleaner.cleanExpiredFiles();

        assertTrue(file.exists(), "未被判定为过期的文件不应该被删除");
        file.delete();
    }
}
