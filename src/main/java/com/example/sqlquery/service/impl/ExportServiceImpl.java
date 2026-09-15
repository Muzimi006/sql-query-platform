package com.example.sqlquery.service.impl;

import com.example.sqlquery.config.RabbitConfig;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.ExportService;
import com.example.sqlquery.vo.ExportTaskVO;
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
        DbSource dbSource = dbSourceService.getById(dto.getDataSourceId());
        if (dbSource == null || !dbSource.getUserId().equals(userId)) {
            throw new BusinessException("数据源不存在");
        }

        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setDataSourceId(dto.getDataSourceId());
        task.setSqlText(dto.getSql());
        task.setStatus("PENDING");
        exportTaskMapper.insert(task);

        rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_QUEUE, task.getId());

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
        if (!"SUCCESS".equals(task.getStatus())) {
            throw new BusinessException("导出任务未完成");
        }
        return task.getFilePath();
    }

    private ExportTaskVO toVO(ExportTask task) {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(task.getId());
        vo.setDataSourceId(task.getDataSourceId());
        vo.setSqlText(task.getSqlText());
        vo.setStatus(task.getStatus());
        vo.setFilePath(task.getFilePath());
        vo.setRowCount(task.getRowCount());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setFinishedAt(task.getFinishedAt());
        return vo;
    }
}
