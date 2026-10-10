package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.entity.QueryHistory;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.QueryHistoryMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.tx.PlatformWrite;
import com.example.sqlquery.tx.PlatformWriteBuffer;
import com.example.sqlquery.vo.QueryHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryHistoryServiceImpl extends ServiceImpl<QueryHistoryMapper, QueryHistory> implements QueryHistoryService{

    private final PlatformWriteBuffer writeBuffer;
    private final SqlPlatformMetrics metrics;

    public QueryHistoryServiceImpl(PlatformWriteBuffer writeBuffer, SqlPlatformMetrics metrics) {
        this.writeBuffer = writeBuffer;
        this.metrics = metrics;
    }

    /**
     * 写一条查询历史。
     *
     * <h2>⚠️ 两条路径，取决于「当前有没有人在收集平台库写」</h2>
     *
     * <p>1. <b>在一个 HTTP 请求里</b>（查询接口）：交给 {@link PlatformWriteBuffer} 收集，
     * 请求结束时由 {@code PlatformWriteFlusher} 与审计<b>合并成一个事务提交</b> ——
     * 一次查询请求的主库提交从 2 次降到 1 次（双1 下就是 4 次 fsync 降到 2 次，口径见档案 G-08）。
     *
     * <p>2. <b>没有收集者时</b>（单元测试、定时任务、MQ 消费者、任何非请求线程）：
     * 直接写库。<b>这条路径必须保留</b> —— 否则「没人在收集」就变成了「数据静默丢失」。
     *
     * <p>失败语义不变：历史是<b>附属品</b>，它没写进去不该改变「这条语句执行成功了」这个事实
     * （见 {@code QueryServiceImpl#saveHistoryQuietly}）。区别只是失败点从「当场」挪到了「提交时」，
     * 由 {@link #historyWrite} 里的 {@code onFailure} 记同一笔账。
     */
    @Override
    public void saveHistory(Long userId, Long dataSourceId, String sql, String status, Long costMs, String errorMessage) {
        QueryHistory history = new QueryHistory();
        history.setUserId(userId);
        history.setDataSourceId(dataSourceId);
        history.setSqlText(sql);
        history.setStatus(status);
        history.setCostMs(costMs);
        history.setErrorMessage(errorMessage);

        if (writeBuffer.isActive()) {
            writeBuffer.add(historyWrite(history));
            return;
        }
        save(history);
    }

    /** 把一次历史写包装成可合并的平台库写（失败只记账，不往外抛）。 */
    private PlatformWrite historyWrite(QueryHistory history) {
        return new PlatformWrite() {
            @Override
            public String name() {
                return "query_history";
            }

            @Override
            public void write() {
                save(history);
            }

            @Override
            public void onFailure(Exception cause) {
                // 与 QueryServiceImpl#saveHistoryQuietly 用的是同一个计数器：
                // 无论失败发生在「当场」还是「合并提交时」，运维看到的都应该是同一个信号。
                metrics.recordHistorySaveFailed();
                log.warn("查询历史写入失败（**本次执行结果不受影响**）。status={}", history.getStatus(), cause);
            }
        };
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
