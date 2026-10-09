package com.example.sqlquery.service.impl;

import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
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
    private final SqlPlatformMetrics metrics;
    private final SqlPermissionService sqlPermissionService;

    /** 单次查询最多返回的行数，超出则截断并置 truncated 标记。 */
    private static final int MAX_ROWS = 1000;

    /** 单条 SQL 的执行超时（秒），防止慢查询长期占用连接。 */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public QueryServiceImpl(DbSourceService dbSourceService,
                            QueryHistoryService queryHistoryService,
                            ConnectionManager connectionManager,
                            SqlPlatformMetrics metrics,
                            SqlPermissionService sqlPermissionService) {
        this.dbSourceService = dbSourceService;
        this.queryHistoryService = queryHistoryService;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
        this.sqlPermissionService = sqlPermissionService;
    }

    @Override
    public QueryVO execute(Long userId, QueryDTO dto) {
        // 走「缓存 + 分布式锁 + 启用状态校验」入口：数据源元数据是查询热路径上读得最多的数据，
        // 直接调 MyBatis-Plus 的 getById 会绕过 Redis 缓存，让缓存形同虚设
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());

        Set<SqlType> allowedTypes = sqlPermissionService.getAllowedTypes(UserContext.getRole());
        // 方言取自这个数据源自己 —— 危险函数与系统库的名单是方言相关的，
        // 拿 MySQL 的名单去检查 PostgreSQL 的 SQL 等于没检查
        SqlValidateUtil.validate(dto.getSql(), allowedTypes, SqlDialect.fromType(dbSource.getType()));

        // 计时从"真正要发给数据库"开始。
        // 注意 SqlValidateUtil.validate 在上面就已经执行过了 —— 被白名单或危险规则
        // 拦下的请求<b>不会</b>进入这里的统计。这是有意的：那些请求根本没碰数据库，
        // 混进耗时分位数里只会把真实的慢查询压下去。
        long start = System.currentTimeMillis();
        try {
            QueryVO vo = doExecute(dbSource, dto.getSql());
            long cost = System.currentTimeMillis() - start;

            metrics.recordQuerySuccess(cost);
            if (Boolean.TRUE.equals(vo.getTruncated())) {
                metrics.recordQueryTruncated();
            }

            queryHistoryService.saveHistory(userId, dbSource.getId(), dto.getSql(), "SUCCESS", cost, null);

            vo.setCostMs(cost);
            return vo;
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;

            metrics.recordQueryFailure(cost);

            queryHistoryService.saveHistory(userId, dbSource.getId(), dto.getSql(), "FAILED", cost, e.getMessage());

            throw new BusinessException("SQL执行失败：" + e.getMessage());
        }
    }

    /**
     * 组装"没有结果集"的执行结果（INSERT / UPDATE / DELETE）。
     *
     * <p>返回空的 `columns`/`rows` 加上影响行数，而不是硬塞一个假的结果集 ——
     * 前端可以据此显示"影响 N 行"，不需要去猜"rows 为空"到底是"查到 0 行"
     * 还是"这条语句本来就不返回数据"。
     */
    private QueryVO buildUpdateResult(Statement statement) throws SQLException {
        QueryVO vo = new QueryVO();
        vo.setColumns(List.of());
        vo.setRows(List.of());
        vo.setTruncated(false);
        vo.setAffectedRows(statement.getUpdateCount());
        return vo;
    }

    private QueryVO doExecute(DbSource dbSource, String sql) throws SQLException {
        try (Connection connection = connectionManager.getConnection(dbSource);
             Statement statement = connection.createStatement()) {

            // 多读一行，用于判断结果是否被截断
            statement.setMaxRows(MAX_ROWS + 1);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            // ⚠️ 用 execute() 而不是 executeQuery()。
            // executeQuery() 只接受"会产生结果集"的语句，对 INSERT/UPDATE/DELETE 会直接抛
            // "Statement.executeQuery() cannot issue statements that do not produce result sets"。
            // 这条限制在"所有角色都只能 SELECT"的时候看不出来，
            // 但一旦按角色放开写语句，就会出现「权限放行了、执行层却做不到」的自相矛盾。
            // execute() 返回"这次执行有没有结果集"，两种情况分别处理。
            boolean hasResultSet = statement.execute(sql);

            if (!hasResultSet) {
                return buildUpdateResult(statement);
            }

            try (ResultSet resultSet = statement.getResultSet()) {
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
