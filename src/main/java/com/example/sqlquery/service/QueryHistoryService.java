package com.example.sqlquery.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.vo.QueryHistoryVO;

import java.util.List;

public interface QueryHistoryService extends IService<QueryHistory> {

    void saveHistory(Long userId, Long dataSourceId, String sql, String status, Long costMs, String errorMessage);

    List<QueryHistoryVO> listByUserId(Long userId);

    IPage<QueryHistoryVO> pageByUserId(Long userId, long current, long size, String status, Long dataSourceId);

    void deleteByIdAndUserId(Long id, Long userId);

    void clearByUserId(Long userId);
}
