package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.config.RabbitConfig;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.ExportService;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.util.RabbitPublishConfirmer;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.ExportTaskVO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    /**
     * 等投递确认最多等多久（毫秒）。
     *
     * <p>正常情况下是毫秒级；给 3 秒是为了容忍 Broker 抖动。
     * <b>等不到就按「没送出去」处理</b>：把任务标失败并告诉用户重试，
     * 而不是留下一条没人管的 PENDING 孤儿行。
     */
    @Value("${export.publish-confirm-timeout-ms:3000}")
    private long publishConfirmTimeoutMs;

    private final ExportTaskMapper exportTaskMapper;
    private final DbSourceService dbSourceService;
    private final RabbitTemplate rabbitTemplate;
    private final SqlPermissionService sqlPermissionService;
    private final SqlPlatformMetrics metrics;
    private final RabbitPublishConfirmer publishConfirmer;

    public ExportServiceImpl(ExportTaskMapper exportTaskMapper,
                             DbSourceService dbSourceService,
                             RabbitTemplate rabbitTemplate,
                             SqlPermissionService sqlPermissionService,
                             SqlPlatformMetrics metrics,
                             RabbitPublishConfirmer publishConfirmer) {
        this.exportTaskMapper = exportTaskMapper;
        this.dbSourceService = dbSourceService;
        this.rabbitTemplate = rabbitTemplate;
        this.sqlPermissionService = sqlPermissionService;
        this.metrics = metrics;
        this.publishConfirmer = publishConfirmer;
    }

    @Override
    public ExportTaskVO createTask(Long userId, ExportTaskDTO dto) {
        // 与查询链路同一个入口：归属校验 + 启用状态校验，两条链路防护等级保持一致
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());

        // 导出**刻意比查询更严**：只允许 SELECT，不跟随角色白名单。
        //
        // 理由：导出的语义是"把查询结果写成文件"。一条 UPDATE 也"执行成功"，
        // 但它没有任何可以写进 CSV 的结果集 —— 放行它只会产生一个内容为空的文件，
        // 而副作用（真的改了数据）已经发生了。
        //
        // 所以这里不复用 getAllowedTypes(role)：即使 ADMIN 被允许在查询里执行写语句，
        // 导出也不该跟着放开。"两条链路共用同一套校验"这句话在这里是有例外的，
        // 而这个例外是收紧而不是放松。
        // 方言同样取自这个数据源：导出链路和查询链路用的是<b>不同</b>的方言实例，
        // 因为它们各自已经取到了自己的 DbSource
        SqlValidateUtil.validate(dto.getSql(), Set.of(SqlType.SELECT),
                SqlDialect.fromType(dbSource.getType()));

        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setDataSourceId(dto.getDataSourceId());
        task.setSqlText(dto.getSql());
        task.setStatus(ExportStatus.PENDING);
        exportTaskMapper.insert(task);

        // 带上 CorrelationData：开启 publisher confirm 后，Broker 的确认/否定
        // 是通过这个对象的 id 回调回来的。不带的话只知道"有一条消息没送到"，
        // 却不知道该把哪条任务标记成失败（见 RabbitConfirmConfig）。
        CorrelationData correlationData = new CorrelationData("export-" + task.getId());

        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_QUEUE, task.getId(), correlationData);

            // ⭐⭐ 光「没抛异常」不等于投递成功 —— 队列满时（x-overflow=reject-publish-dlx）
            //    Broker 是**异步否定确认**，convertAndSend 照样平静返回。
            //    以前这里只有下面那个 catch，于是「队列满」这类拒绝完全走不到它：
            //    任务留在 PENDING 等 10 分钟被补偿任务判失败，而用户当场看到的是「导出成功」。
            if (!publishConfirmer.awaitConfirmed(correlationData, publishConfirmTimeoutMs,
                    SqlPlatformMetrics.LINK_EXPORT)) {
                throw new AmqpException("导出任务未能取得 Broker 确认（队列满 / Broker 抖动）");
            }
        } catch (Exception e) {
            // ⚠️ 投递失败时必须**立刻把任务标掉**，否则会留下一条永远 PENDING 的孤儿行：
            //    它既不会被执行，也要等 10 分钟才被补偿任务判失败，而用户当场看到的是
            //    "系统异常"（异常冒到全局兜底处理器）。
            //
            // 为什么不像工单链路那样"吞掉异常、交给补偿重投"：
            // **导出没有补偿重投** —— 重跑会覆盖同一个文件名，中途中断留下的半成品
            // 和重跑的结果无法区分（见 ExportTaskRecoveryJob 的类注释）。
            // 所以这里只能现在就把它标失败，并给用户一句能看懂的话。
            metrics.recordPublishFailure(SqlPlatformMetrics.LINK_EXPORT);
            log.error("导出任务创建后投递失败，已立即标记为失败。taskId={}", task.getId(), e);
            exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                    .eq(ExportTask::getId, task.getId())
                    .eq(ExportTask::getStatus, ExportStatus.PENDING)
                    .set(ExportTask::getStatus, ExportStatus.FAILED)
                    .set(ExportTask::getErrorMessage, "任务未能投递到消息队列，请稍后重新发起导出")
                    .set(ExportTask::getFinishedAt, LocalDateTime.now()));
            throw new BusinessException("导出任务创建失败，请稍后重试");
        }

        return toVO(task);
    }

    @Override
    public List<ExportTaskVO> listByUserId(Long userId) {
        return exportTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExportTask>()
                        .eq(ExportTask::getUserId, userId)
                        .orderByDesc(ExportTask::getCreatedAt)
        ).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public String getDownloadPath(Long userId, Long id) {
        ExportTask task = exportTaskMapper.selectById(id);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException("导出任务不存在");
        }
        if (!ExportStatus.SUCCESS.equals(task.getStatus())) {
            throw new BusinessException("导出任务未完成");
        }
        if (task.getFilePath() == null) {
            // 文件已被 ExportFileCleaner 清理掉。
            // 这个分支是必需的：不判的话 controller 里 new File(null) 会直接 NPE，
            // 用户看到的是「系统异常」而不是「文件已过期」
            throw new BusinessException("导出文件已过期清理，请重新导出");
        }
        return task.getFilePath();
    }

    private ExportTaskVO toVO(ExportTask task) {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(task.getId());
        vo.setDataSourceId(task.getDataSourceId());
        vo.setSqlText(task.getSqlText());
        vo.setStatus(task.getStatus());
        // 只下发「能不能下载」，不下发服务器路径
        vo.setDownloadable(ExportStatus.SUCCESS.equals(task.getStatus()) && task.getFilePath() != null);
        vo.setRowCount(task.getRowCount());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setFinishedAt(task.getFinishedAt());
        return vo;
    }
}
