package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.mapper.QueryHistoryMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 平台自己那几张日志类表的保留策略。
 *
 * <h2>它补的是一个什么样的洞</h2>
 *
 * <p>在此之前，{@code export_task} / {@code audit_log} / {@code query_history} <b>只会涨</b>：
 * 没有任何清理任务、没有分区、也没有归档。这三张表的写入量是<b>跟着请求走的</b>
 * （查询一次写 2 张：审计 + 历史），所以只要平台在跑，它们就在涨 ——
 * 直到磁盘满或索引大到查询变慢，<b>而那是几个小时之内从"正常"变成"不可用"的过程</b>。
 *
 * <h2>⚠️ 为什么默认关闭（{@code retention.enabled: false}）</h2>
 *
 * <p>因为<b>删除是不可逆的</b>。保留多久是业务/合规决定，不是技术决定：
 * 审计日志可能是等保要求的留存证据，查询历史可能是用户要翻的记录。
 * 应用<b>没有资格替部署方决定"多少天可以删"</b> ——
 * 所以这里做成显式开关：默认什么都不删，开了才删，而且删多少有指标可查。
 *
 * <p>「不修也是一种决定，但必须写下来」这条项目惯例在这里变成了：
 * <b>机制必须存在、默认必须关闭、理由必须写在配置旁边。</b>
 *
 * <h2>它怎么删（三个刻意的设计）</h2>
 *
 * <ol>
 *   <li><b>先查 id 再按 id 删</b>，而不是 {@code DELETE ... LIMIT n}：
 *       MySQL 支持 DELETE 带 LIMIT，<b>PostgreSQL 不支持</b> ——
 *       虽然平台库固定是 MySQL，但"依赖某一种方言的语法"是没必要的耦合，
 *       而且按主键删还能走主键索引；</li>
 *   <li><b>分批 + 每轮有上限</b>：一次删几百万行会长时间持锁、把 undo 撑爆、
 *       还会和正常流量抢平台库连接。每批 {@code batch-size} 行，
 *       单轮最多 {@code max-batches-per-run} 批，删不完留给下一次；</li>
 *   <li><b>逐表捕获异常</b>：一张表失败（或者被锁住）不该让另外两张也停下。</li>
 * </ol>
 *
 * <h2>⚠️ 它守不住什么</h2>
 *
 * <p>它<b>不做归档</b>：删掉就是删掉，不会先导出到别处。
 * 需要留证据的场景应当先把数据同步出去（例如按 {@code created_at} 定期导出），
 * 再打开这个开关。它也不做分区表 —— 那种方案清理更快（直接 DROP PARTITION），
 * 但要改 DDL 且需要一次性迁移历史数据，属于另一轮。
 */
@Slf4j
@Component
public class DataRetentionJob {

    private final ExportTaskMapper exportTaskMapper;
    private final AuditLogMapper auditLogMapper;
    private final QueryHistoryMapper queryHistoryMapper;
    private final SqlPlatformMetrics metrics;

    /** 总开关。默认关闭，理由见类注释。 */
    private final boolean enabled;

    /** 每批删除的行数上限。 */
    private final int batchSize;

    /** 单轮每个表最多删几批 —— 防止一次清理把平台库占满。 */
    private final int maxBatchesPerRun;

    private final int exportTaskDays;
    private final int auditLogDays;
    private final int queryHistoryDays;

    public DataRetentionJob(
            ExportTaskMapper exportTaskMapper,
            AuditLogMapper auditLogMapper,
            QueryHistoryMapper queryHistoryMapper,
            SqlPlatformMetrics metrics,
            @Value("${retention.enabled:false}") boolean enabled,
            @Value("${retention.batch-size:1000}") int batchSize,
            @Value("${retention.max-batches-per-run:10}") int maxBatchesPerRun,
            @Value("${retention.export-task-days:30}") int exportTaskDays,
            @Value("${retention.audit-log-days:180}") int auditLogDays,
            @Value("${retention.query-history-days:90}") int queryHistoryDays) {
        this.exportTaskMapper = exportTaskMapper;
        this.auditLogMapper = auditLogMapper;
        this.queryHistoryMapper = queryHistoryMapper;
        this.metrics = metrics;
        this.enabled = enabled;
        // 批次大小至少 1：配 0 会让 LIMIT 0 永远查不到东西，表现为"清理跑了但什么都没删"
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
        this.exportTaskDays = exportTaskDays;
        this.auditLogDays = auditLogDays;
        this.queryHistoryDays = queryHistoryDays;
    }

    /**
     * 每天凌晨清理一次。
     *
     * <p>选凌晨是因为它要删数据、要占平台库连接 —— 放在业务高峰做这件事，
     * 等于用一个后台任务去和用户查询抢资源。
     */
    @Scheduled(cron = "${retention.cron:0 30 3 * * ?}")
    public void purge() {
        if (!enabled) {
            // 关闭时不打日志：默认就是关的，每天打一条只会变成噪音
            return;
        }

        int total = 0;
        total += purgeTarget("export_task", exportTaskDays,
                cutoff -> exportTaskMapper.selectList(new LambdaQueryWrapper<ExportTask>()
                                .select(ExportTask::getId)
                                .lt(ExportTask::getCreatedAt, cutoff)
                                // 按主键升序：先删最老的，删不完也是"最老的先走"
                                .orderByAsc(ExportTask::getId)
                                .last("LIMIT " + batchSize))
                        .stream().map(ExportTask::getId).toList(),
                exportTaskMapper::deleteBatchIds);

        total += purgeTarget("audit_log", auditLogDays,
                cutoff -> auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                                .select(AuditLog::getId)
                                .lt(AuditLog::getCreatedAt, cutoff)
                                .orderByAsc(AuditLog::getId)
                                .last("LIMIT " + batchSize))
                        .stream().map(AuditLog::getId).toList(),
                auditLogMapper::deleteBatchIds);

        total += purgeTarget("query_history", queryHistoryDays,
                cutoff -> queryHistoryMapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                                .select(QueryHistory::getId)
                                .lt(QueryHistory::getCreatedAt, cutoff)
                                .orderByAsc(QueryHistory::getId)
                                .last("LIMIT " + batchSize))
                        .stream().map(QueryHistory::getId).toList(),
                queryHistoryMapper::deleteBatchIds);

        if (total > 0) {
            log.info("保留策略清理完成，本轮共删除 {} 行。各表保留天数：export_task={}d, "
                    + "audit_log={}d, query_history={}d", total,
                    exportTaskDays, auditLogDays, queryHistoryDays);
        }
    }

    /**
     * 清理一张表。
     *
     * @param target      表名（同时作为指标标签 —— <b>固定三个取值</b>，不能拼动态字符串）
     * @param days        保留天数
     * @param selectIds   按 cutoff 查出待删除的 id（最多 batchSize 个）
     * @param deleteByIds 按 id 删除，返回实际删除行数
     * @return 本轮实际删除的行数
     */
    private int purgeTarget(String target, int days,
                            Function<LocalDateTime, List<Long>> selectIds,
                            Function<List<Long>, Integer> deleteByIds) {
        // ⚠️ 这一道判断是整个类里最重要的一行。
        //    days <= 0 的后果是 cutoff = now → **把所有数据删光**，而且不可恢复。
        //    配置项出现 0 的现实路径是"占位符没被替换"或"手滑少打一位"——
        //    这两种情况都不该以"删库"的形式表现出来。所以这里宁可跳过并喊一声。
        if (days <= 0) {
            log.warn("retention.{}-days={} 不是正整数 —— 这一项<b>本轮被跳过</b>。"
                            + "保留天数必须为正；0 或负数会让清理变成\"删光整张表\"。",
                    target, days);
            return 0;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int deleted = 0;

        for (int round = 1; round <= maxBatchesPerRun; round++) {
            List<Long> ids;
            try {
                ids = selectIds.apply(cutoff);
            } catch (Exception e) {
                metrics.recordRetentionFailed(target);
                log.error("保留策略：查询 {} 的待删除记录失败，本轮跳过这一张表。cutoff={}",
                        target, cutoff, e);
                return deleted;
            }

            if (ids.isEmpty()) {
                return deleted;
            }

            try {
                int rows = deleteByIds.apply(ids);
                deleted += rows;
                metrics.recordRetentionDeleted(target, rows);
            } catch (Exception e) {
                metrics.recordRetentionFailed(target);
                log.error("保留策略：删除 {} 失败（已删 {} 行），本轮停止这一张表",
                        target, deleted, e);
                return deleted;
            }

            // 查到的比一批还少 ⇒ 后面没有了，不用再多跑一次空查询
            if (ids.size() < batchSize) {
                return deleted;
            }
        }

        // 跑到上限还没删完：这不是错误（下一次还会继续），但必须说出来 ——
        // 否则"每天只删 10000 行、而每天新增 5 万行"这件事会一直看不出来
        log.warn("保留策略：{} 本轮已删满 {} 批（{} 行）仍有剩余，下一轮继续。"
                + "持续出现这条日志说明保留天数或批次上限需要调整。",
                target, maxBatchesPerRun, deleted);
        return deleted;
    }
}
