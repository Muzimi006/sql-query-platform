package com.example.sqlquery.service.impl;

import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.QueryVO;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QueryServiceImpl implements QueryService {

    private final DbSourceService dbSourceService;
    private final QueryHistoryService queryHistoryService;
    private final ConnectionManager connectionManager;
    private static final int MAX_ROWS = 1000;

    public QueryServiceImpl(DbSourceService dbSourceService, QueryHistoryService queryHistoryService, AesUtil aesUtil, ConnectionManager connectionManager) {
        this.dbSourceService = dbSourceService;
        this.queryHistoryService = queryHistoryService;
        this.connectionManager = connectionManager;
    }

    @Override
    public QueryVO execute(Long userId, QueryDTO dto) {
        DbSource dbSource = dbSourceService.getById(dto.getDataSourceId());

        if (dbSource == null || !dbSource.getUserId().equals(userId)) {
            throw new BusinessException("数据源不存在");
        }
        SqlValidateUtil.validateSelect(dto.getSql());
        long start = System.currentTimeMillis();
        try {
            QueryVO vo = doExecute(dbSource, dto.getSql());
            long cost = System.currentTimeMillis() - start;

            queryHistoryService.saveHistory(
                    userId,
                    dbSource.getId(),
                    dto.getSql(),
                    "SUCCESS",
                    cost,
                    null
            );

            vo.setCostMs(cost);
            return vo;
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;

            queryHistoryService.saveHistory(
                    userId,
                    dbSource.getId(),
                    dto.getSql(),
                    "FAILED",
                    cost,
                    e.getMessage()
            );

            throw new BusinessException("SQL执行失败：" + e.getMessage());
        }
    }

    private QueryVO doExecute(DbSource dbSource, String sql) throws SQLException {

        try (Connection connection = connectionManager.getConnection(dbSource);
                
                
                
             Statement statement = connection.createStatement()) {
             // result set moved to inner try below

            statement.setMaxRows(MAX_ROWS + 1);

            statement.setQueryTimeout(30);

            try (ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnLabel(i));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                }
                rows.add(row);
            }

            QueryVO vo = new QueryVO();
            vo.setColumns(columns);

            boolean truncated = rows.size() > MAX_ROWS;
            if (truncated) {
                rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
            }
            vo.setRows(rows);
            vo.setTruncated(truncated);
            return vo;
            }
        }
    }
}
