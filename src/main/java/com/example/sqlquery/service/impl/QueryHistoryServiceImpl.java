package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.QueryHistoryMapper;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.vo.QueryHistoryVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryHistoryServiceImpl extends ServiceImpl<QueryHistoryMapper, QueryHistory> implements QueryHistoryService{

    @Override
    public void saveHistory(Long userId, Long dataSourceId, String sql, String status, Long costMs, String errorMessage) {
        QueryHistory history = new QueryHistory();
        history.setUserId(userId);
        history.setDataSourceId(dataSourceId);
        history.setSqlText(sql);
        history.setStatus(status);
        history.setCostMs(costMs);
        history.setErrorMessage(errorMessage);
        save(history);
    }

    @Override
    public List<QueryHistoryVO> listByUserId(Long userId) {
        LambdaQueryWrapper<QueryHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QueryHistory::getUserId, userId)
                .orderByDesc(QueryHistory::getCreatedAt);
        return list(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public IPage<QueryHistoryVO> pageByUserId(Long userId, long current, long size, String status, Long dataSourceId) {
        Page<QueryHistory> page = new Page<>(current, size);
        LambdaQueryWrapper<QueryHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QueryHistory::getUserId, userId);

        if (status != null && !status.isEmpty()) {
            wrapper.eq(QueryHistory::getStatus, status);
        }
        if (dataSourceId != null) {
            wrapper.eq(QueryHistory::getDataSourceId, dataSourceId);
        }

        wrapper.orderByDesc(QueryHistory::getCreatedAt);

        Page<QueryHistory> result = page(page, wrapper);

        Page<QueryHistoryVO> voPage = new Page<>(current, size, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public void deleteByIdAndUserId(Long id, Long userId) {
        QueryHistory exist = getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BusinessException("历史记录不存在");
        }
        removeById(id);
    }

    @Override
    public void clearByUserId(Long userId) {
        LambdaQueryWrapper<QueryHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QueryHistory::getUserId, userId);
        remove(wrapper);
    }

    private QueryHistoryVO toVO(QueryHistory history) {
        QueryHistoryVO vo = new QueryHistoryVO();
        vo.setId(history.getId());
        vo.setDataSourceId(history.getDataSourceId());
        vo.setSqlText(history.getSqlText());
        vo.setStatus(history.getStatus());
        vo.setCostMs(history.getCostMs());
        vo.setErrorMessage(history.getErrorMessage());
        vo.setCreatedAt(history.getCreatedAt());
        return vo;
    }
}
