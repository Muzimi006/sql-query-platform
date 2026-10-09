package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.config.RabbitConfig;
import com.example.sqlquery.config.SqlPermissionConfig;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.ExportService;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.ExportTaskVO;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExportServiceImpl implements ExportService {

    private final ExportTaskMapper exportTaskMapper;
    private final DbSourceService dbSourceService;
    private final RabbitTemplate rabbitTemplate;

    public ExportServiceImpl(ExportTaskMapper exportTaskMapper,
                             DbSourceService dbSourceService,
                             RabbitTemplate rabbitTemplate) {
        this.exportTaskMapper = exportTaskMapper;
        this.dbSourceService = dbSourceService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public ExportTaskVO createTask(Long userId, ExportTaskDTO dto) {
        // 与查询链路同一个入口：归属校验 + 启用状态校验，两条链路防护等级保持一致
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());

        // 导出链路与查询链路共用同一套 SQL 审核与角色白名单，避免出现绕过防线的一致性缺口
        SqlValidateUtil.validate(dto.getSql(), SqlPermissionConfig.getByRole(UserContext.getRole()));

        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setDataSourceId(dto.getDataSourceId());
        task.setSqlText(dto.getSql());
        task.setStatus(ExportStatus.PENDING);
        exportTaskMapper.insert(task);

        // 带上 CorrelationData：开启 publisher confirm 后，Broker 的确认/否定
        // 是通过这个对象的 id 回调回来的。不带的话只知道"有一条消息没送到"，
        // 却不知道该把哪条任务标记成失败（见 RabbitConfirmConfig）。
        rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_QUEUE, task.getId(),
                new CorrelationData(String.valueOf(task.getId())));

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
