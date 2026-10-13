package com.example.sqlquery.job;

import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.mapper.QueryHistoryMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 保留策略的**端到端**验证：它真的会删、也真的只删该删的。
 *
 * <h2>为什么不能只靠单元测试</h2>
 *
 * <p>@code DataRetentionJobTest 用的是 mock 的 mapper —— 它证明的是「调用顺序对不对」，
 * 不是「SQL 真的删掉了那些行」。而这条链路上最容易错的地方恰恰在 SQL 层：
 * 筛选条件写错（比如把 created_at 写成别的列）、批次上限没生效、
 * **或者更糟：把不该删的也删了**。
 *
 * <h2>⚠️ 怎么做到「不误删真实数据」</h2>
 *
 * <p>这条用例跑在**开发用的那个库**上，里面有人工测试留下的真实数据。
 * 如果直接开 retention.enabled=true + 保留 1 天，用例会把它们一起删掉。
 * 所以这里把保留天数设成 **36500 天（100 年）**：
 * cutoff 变成「100 年前的今天」，只有**我自己造的、时间戳在 1900 年**的探针行会被删。
 *
 * <p>换句话说：<b>用例验的是「删对了」，而不是「删得多」</b> ——
 * 「删得多」那部分由单元测试（阈值、批次、天数 ≤ 0 跳过）覆盖。
 */
@Tag("integration")
@SpringBootTest(properties = {
        "retention.enabled=true",
        // 100 年：cutoff 落在 1900 年代之外，真实数据一条都不会被碰到
        "retention.audit-log-days=36500",
        "retention.query-history-days=36500",
        "retention.export-task-days=36500",
        "retention.batch-size=100",
        "retention.max-batches-per-run=5"
})
class DataRetentionJobIntegrationTest {

    private static final Long PROBE_USER_ID = 990_007L;

    @Autowired
    private DataRetentionJob retentionJob;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private QueryHistoryMapper queryHistoryMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    private final List<Long> probeAuditIds = new ArrayList<>();
    private final List<Long> probeHistoryIds = new ArrayList<>();

    private AuditLog auditRow(LocalDateTime createdAt) {
        AuditLog row = new AuditLog();
        row.setUserId(PROBE_USER_ID);
        row.setAction("PROBE");
        row.setDetail("保留策略端到端验证探针");
        row.setIp("127.0.0.1");
        row.setCreatedAt(createdAt);
        auditLogMapper.insert(row);
        probeAuditIds.add(row.getId());
        return row;
    }

    private QueryHistory historyRow(LocalDateTime createdAt) {
        QueryHistory row = new QueryHistory();
        row.setUserId(PROBE_USER_ID);
        row.setDataSourceId(1L);
        row.setSqlText("SELECT 1");
        row.setStatus("SUCCESS");
        row.setCostMs(1L);
        row.setCreatedAt(createdAt);
        queryHistoryMapper.insert(row);
        probeHistoryIds.add(row.getId());
        return row;
    }

    private void cleanProbes() {
        probeAuditIds.forEach(auditLogMapper::deleteById);
        probeHistoryIds.forEach(queryHistoryMapper::deleteById);
        probeAuditIds.clear();
        probeHistoryIds.clear();
    }

    /**
     * 把 retention.deleted **按 target 分组的几条时间线加起来**。
     *
     * <p>⚠️ 这里踩过一次：第一版写的是 meterRegistry.find(name).counter() ——
     * 它只返回**其中一条**（这个指标按 target 分组，有几条时间线），
     * 于是两条探针明明都被删了，断言看到的却是 1.0。
     * **指标带标签时，取数必须明确是「求和」还是「取某一条」。**
     */
    private double deletedTotal() {
        return meterRegistry.find(SqlPlatformMetrics.RETENTION_DELETED).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Test
    @DisplayName("⭐ 开启后真的会删：1900 年的探针行被删掉，刚写入的一条原样保留")
    void purgeShouldReallyDeleteOldRows() {
        try {
            AuditLog ancient = auditRow(LocalDateTime.of(1900, 1, 1, 0, 0));
            QueryHistory ancientHistory = historyRow(LocalDateTime.of(1900, 1, 1, 0, 0));
            // 对照组：刚写入的行，任何保留策略都不该动它
            AuditLog fresh = auditRow(LocalDateTime.now());
            QueryHistory freshHistory = historyRow(LocalDateTime.now());

            double before = deletedTotal();
            retentionJob.purge();
            double deleted = deletedTotal() - before;
            System.out.printf("【实测】保留策略一次清理：retention.deleted +%.0f 行%n", deleted);

            assertNull(auditLogMapper.selectById(ancient.getId()),
                    "1900 年的审计行应当被删掉 —— 没删说明批次日/筛选条件没生效");
            assertNull(queryHistoryMapper.selectById(ancientHistory.getId()),
                    "1900 年的历史行应当被删掉");
            assertNotNull(auditLogMapper.selectById(fresh.getId()),
                    "刚写入的行**绝不能**被删（这是最关键的一条：误删不可恢复）");
            assertNotNull(queryHistoryMapper.selectById(freshHistory.getId()));
            assertEquals(2.0, deleted,
                    "两条探针应当被删掉，且计数要如实反映（多出来的说明误删了别的数据）");
        } finally {
            cleanProbes();
        }
    }
}
