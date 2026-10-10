package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出任务的"没人管"状态补偿。
 *
 * <h2>它管的是两种卡死</h2>
 *
 * <p>导出是一条<b>跨了三个组件</b>的链路：数据库（任务记录）→ MQ（投递）→ 消费者（干活）。
 * 每一段都有自己失败的方式，而失败的结果都是一个<b>不会自己变的状态</b>：
 *
 * <table border="1">
 *   <caption>两种卡死</caption>
 *   <tr><th>卡在</th><th>意味着</th><th>典型原因</th></tr>
 *   <tr>
 *     <td>{@link ExportStatus#PENDING}</td>
 *     <td>消息根本没被消费到</td>
 *     <td>Broker 否定确认、路由失败、RabbitMQ 当时不可用</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ExportStatus#RUNNING}</td>
 *     <td>消费者拿了消息但没能把结果写回去</td>
 *     <td>进程被 kill、连接池打满、写库时数据库不可用</td>
 *   </tr>
 * </table>
 *
 * <p>没有这个任务时，两条路都会让前端永远转圈，而且<b>没有任何东西会来纠正</b>：
 * 重复投递被幂等检查挡掉（状态已经不是 PENDING），数据库里也不会自己变。
 * 现实中这个坑是一次"导出等了 30 秒还是 RUNNING"暴露出来的。
 *
 * <h2>为什么标记成 FAILED，而不是自动重跑</h2>
 *
 * <p>重跑的前提是"跑两次和跑一次结果一样"。当前实现直接覆盖同一个文件名，
 * 中途中断留下的半成品文件和重跑的结果<b>无法区分</b> ——
 * 在没有这个保证之前自动重跑，有把错误文件当成结果交给用户的风险。
 * 让用户重新发起一次导出成本很低，所以这里选择<b>明确失败</b>而不是<b>悄悄重试</b>。
 *
 * <p>要做到自动重跑，前置改动是：先把导出改成"写临时文件 → 写完整 → 原子改名"，
 * 让"文件存在"本身就代表"内容是完整的"。
 *
 * <h2>已知的边界（显式说明，不是漏了）</h2>
 *
 * <p>PENDING 的超时判定用的是"创建时间超过阈值"。
 * 如果队列里积压了大量任务、前面的任务跑得很慢，一个<b>正常排队中</b>的任务
 * 也会被判为失败。当前任务量下这个阈值（默认 10 分钟）足够宽松；
 * 真要做对，应该改成让消费者上报心跳而不是看创建时间。
 */
@Slf4j
@Component
public class ExportTaskRecoveryJob {

    private final ExportTaskMapper exportTaskMapper;

    /**
     * 超过这个时长还停在 PENDING / RUNNING，就认定它已经卡死。
     *
     * <h3>取值依据</h3>
     *
     * <p>这个值不是拍脑袋定的，它和 {@code export.query-timeout-seconds} <b>有明确的约束关系</b>：
     *
     * <pre>
     *   一条导出 SQL 最坏情况下跑满 query-timeout（默认 5 分钟）后被 JDBC 中断
     *   + 写文件和清理的时间（秒级）
     *   ────────────────────────────────────────────
     *   = 一个正常任务的"执行阶段"不可能超过约 6 分钟
     *
     *   而这里的判定用的是 created_at —— 它<b>还包含在队列里排队的时间</b>
     *   所以阈值 = 执行上限 + 可接受的排队时间
     *   取 10 分钟 ≈ 6 分钟执行 + 4 分钟排队余量
     * </pre>
     *
     * <p>⚠️ <b>这两个值的关系是有约束的</b>：如果哪天把 {@code query-timeout-seconds}
     * 调到接近或超过这个阈值，补偿任务就会把<b>仍在正常执行</b>的导出判成卡死 ——
     * 而消费者跑完之后又会把结果写回去，用户看到的状态会前后矛盾。
     * <b>调大导出超时时，必须同步调大这个阈值。</b>
     *
     * <p>（最初这里写的是"单次导出的 SQL 有 30 秒执行超时"—— 那是照抄查询链路的数字，
     * 而导出链路当时<b>根本没有设超时</b>。现在两边的值对上了，
     * 而且这个约束关系写在注释里，改一个值的时候能看到另一个。）
     */
    @Value("${export.stuck-running-minutes:10}")
    private int stuckRunningMinutes;

    public ExportTaskRecoveryJob(ExportTaskMapper exportTaskMapper) {
        this.exportTaskMapper = exportTaskMapper;
    }

    /** 默认每 5 分钟扫一次。 */
    @Scheduled(cron = "${export.recovery-cron:0 */5 * * * ?}")
    public void failStuckTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(stuckRunningMinutes);

        // ⭐ 两种卡死看的**不是同一个时间列**，这一点很关键：
        //
        //   RUNNING → started_at（认领时刻）—— 关心的是"执行了多久"
        //   PENDING → created_at（创建时刻）—— 关心的是"提交后多久还没被认领"，
        //              而那时候还没有 started_at 可看
        //
        // 以前两者都用 created_at，于是 RUNNING 的判定把**排队时间**也算进去了：
        // 队列积压时「排队 6 分钟 + 执行 5 分钟」的正常任务会被判成卡死，
        // 补偿任务标 FAILED，消费者随后又写回 SUCCESS ——
        // 用户看到"导出成功"却配着一句"已由补偿任务标记为失败"。
        failStuck(ExportStatus.RUNNING, deadline,
                "任务执行中断（进程重启或消费者异常），已由补偿任务标记为失败，请重新发起导出",
                ExportTask::getStartedAt);
        failStuck(ExportStatus.PENDING, deadline,
                "任务未能投递到消息队列，已由补偿任务标记为失败，请重新发起导出",
                ExportTask::getCreatedAt);
    }

    /**
     * 把处于 {@code stuckStatus} 且 {@code timeColumn} 早于 {@code deadline} 的任务标记为失败。
     *
     * @param timeColumn 用哪个时间列判定 —— RUNNING 传 {@code startedAt}、
     *                   PENDING 传 {@code createdAt}，理由见 {@link #failStuckTasks()}
     */
    private void failStuck(String stuckStatus, LocalDateTime deadline, String reason,
                           SFunction<ExportTask, ?> timeColumn) {
        List<ExportTask> stuck = exportTaskMapper.selectList(
                new LambdaQueryWrapper<ExportTask>()
                        .eq(ExportTask::getStatus, stuckStatus)
                        .lt(timeColumn, deadline));

        if (stuck.isEmpty()) {
            return;
        }

        for (ExportTask task : stuck) {
            // 条件更新加一道保险：只有<b>仍然</b>停在这个状态才改。
            // 不用 updateById 是因为"查出来 → 改 → 写回"中间有时间窗，
            // 任务可能已经被消费者正常完成了，那样会把 SUCCESS 覆盖成 FAILED。
            int affected = exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                    .eq(ExportTask::getId, task.getId())
                    .eq(ExportTask::getStatus, stuckStatus)
                    .set(ExportTask::getStatus, ExportStatus.FAILED)
                    .set(ExportTask::getErrorMessage, reason)
                    .set(ExportTask::getFinishedAt, LocalDateTime.now()));

            if (affected == 1) {
                log.warn("导出任务长时间停留在 {}，已标记为失败。taskId={}, 超时阈值={}分钟",
                        stuckStatus, task.getId(), stuckRunningMinutes);
            } else {
                log.info("导出任务在补偿时状态已变化（可能刚被完成），跳过。taskId={}", task.getId());
            }
        }
    }
}
