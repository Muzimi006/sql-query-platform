package com.example.sqlquery.service;

import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.vo.ExportTaskVO;

import java.util.List;

public interface ExportService {

    ExportTaskVO createTask(Long userId, ExportTaskDTO dto);

    List<ExportTaskVO> listByUserId(Long userId);

    String getDownloadPath(Long userId, Long id);
}
