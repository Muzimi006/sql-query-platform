package com.example.sqlquery.service.impl;

import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.config.SqlPermissionConfig;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.QueryVO;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QueryServiceImpl implements QueryService {

    private final DbSourceService dbSourceService;
    private final QueryHistoryService queryHistoryService;
    private final ConnectionManager connectionManager;

    /** 单次查询最多返回的行数，超出则截断并置 truncated 标记。 */
    private static final int MAX_ROWS = 1000;

    /** 单条 SQL 的执行超时（秒），防止慢查询长期占用连接。 */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public QueryServiceImpl(DbSourceService dbSourceService,
                            QueryHistoryService queryHistoryService,
                            ConnectionManager connectionManager) {
        this.dbSourceService = dbSourceService;
        this.queryHistoryService = queryHistoryService;
        this.connectionManager = connectionManager;
    }

    @Override
    public QueryVO execute(Long userId, QueryDTO dto) {
        // 走「缓存 + 分布式锁 + 启用状态校验」入口：数据源元数据是查询热路径上读得最多的数据，
        // 直接调 MyBatis-Plus 的 getById 会绕过 Redis 缓存，让缓存形同虚设
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());

        Set<SqlType> allowedTypes = SqlPermissionConfig.getByRole(UserContext.getRole());
        SqlValidateUtil.validate(dto.getSql(), allowedTypes);

        long start = System.currentTimeMillis();
        try {
            QueryVO vo = doExecute(dbSource, dto.getSql());
            long cost = System.currentTimeMillis() - start;

            queryHistoryService.saveHistory(userId, dbSource.getId(), dto.getSql(), "SUCCESS", cost, null);

            vo.setCostMs(cost);
            return vo;
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;

            queryHistoryService.saveHistory(userId, dbSource.getId(), dto.getSql(), "FAILED", cost, e.getMessage());

            throw new BusinessException("SQL执行失败：" + e.getMessage());
        }
    }

    private QueryVO doExecute(DbSource dbSource, String sql) throws SQLException {
        try (Connection connection = connectionManager.getConnection(dbSource);
             Statement statement = connection.createStatement()) {

            // 多读一行，用于判断结果是否被截断
            statement.setMaxRows(MAX_ROWS + 1);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

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
