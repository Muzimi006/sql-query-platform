package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出文件的生命周期管理。
 *
 * <p>导出产生的 CSV 会一直躺在 {@code exports/} 目录里。没有清理任务的话，
 * 磁盘会被慢慢吃满 —— <b>功能做完了，但生命周期没人管</b>，
 * 这是异步任务最常见的一个缺口。
 *
 * <p>删文件的同时把 {@code export_task.file_path} 置空。这样列表接口只看数据库
 * 就知道"这个任务还能不能下载"，不需要对每条记录做一次文件系统 stat。
 */
@Slf4j
@Component
public class ExportFileCleaner {

    private final ExportTaskMapper exportTaskMapper;

    /** 导出文件保留天数。 */
    @Value("${export.file-retention-days:7}")
    private int retentionDays;

    public ExportFileCleaner(ExportTaskMapper exportTaskMapper) {
        this.exportTaskMapper = exportTaskMapper;
    }

    /** 默认每天凌晨 3 点执行。 */
    @Scheduled(cron = "${export.clean-cron:0 0 3 * * ?}")
    public void cleanExpiredFiles() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);

        List<ExportTask> expired = exportTaskMapper.selectList(
                new LambdaQueryWrapper<ExportTask>()
                        .isNotNull(ExportTask::getFilePath)
                        .lt(ExportTask::getCreatedAt, deadline));

        if (expired.isEmpty()) {
            return;
        }

        int cleaned = 0;
        for (ExportTask task : expired) {
            File file = new File(task.getFilePath());

            // file.exists() 为 false 说明文件已经不在了（比如被手工删过），
            // 这种情况同样要把数据库里的路径清掉，否则接口会一直说"可以下载"
            if (file.exists() && !file.delete()) {
                // 删不掉就这一轮跳过，保留数据库记录留待下次重试 ——
                // 不能出现「文件还在，但已经标记为不可下载」的状态
                log.warn("导出文件删除失败，留待下次重试。taskId={}, path={}",
                        task.getId(), task.getFilePath());
                continue;
            }

            // ⚠️ 不能写成 updateById(task) —— MyBatis-Plus 的 updateById 默认忽略 null 字段，
            // 那样生成的 SQL 根本不会带上 file_path，等于没清
            exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                    .eq(ExportTask::getId, task.getId())
                    .set(ExportTask::getFilePath, null));
            cleaned++;
        }

        log.info("导出文件清理完成：扫描 {} 个，清理 {} 个，保留 {} 天",
                expired.size(), cleaned, retentionDays);
    }
}
