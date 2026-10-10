package com.example.sqlquery.service.impl;

import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.exception.RejectedBeforeWorkException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryHistoryService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.DbSourceCircuitBreaker;
import com.example.sqlquery.util.QueryConcurrencyGuard;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.QueryVO;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class QueryServiceImpl implements QueryService {

    private final DbSourceService dbSourceService;
    private final QueryHistoryService queryHistoryService;
    private final ConnectionManager connectionManager;
    private final SqlPlatformMetrics metrics;
    private final SqlPermissionService sqlPermissionService;
    private final QueryConcurrencyGuard concurrencyGuard;
    private final DbSourceCircuitBreaker circuitBreaker;

    /** 单次查询最多返回的行数，超出则截断并置 truncated 标记。 */
    private static final int MAX_ROWS = 1000;

    /** 单条 SQL 的执行超时（秒），防止慢查询长期占用连接。 */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public QueryServiceImpl(DbSourceService dbSourceService,
                            QueryHistoryService queryHistoryService,
                            ConnectionManager connectionManager,
                            SqlPlatformMetrics metrics,
                            SqlPermissionService sqlPermissionService,
                            QueryConcurrencyGuard concurrencyGuard,
                            DbSourceCircuitBreaker circuitBreaker) {
        this.dbSourceService = dbSourceService;
        this.queryHistoryService = queryHistoryService;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
        this.sqlPermissionService = sqlPermissionService;
        this.concurrencyGuard = concurrencyGuard;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public QueryVO execute(Long userId, QueryDTO dto) {
        // ⚠️ 并发闸门必须在<b>最前面</b>，而且它是**非阻塞**的。
        //
        // 这里是全项目唯一一个被连接池卡住的地方：每个业务数据源的池子只有
        // ConnectionManager.MAX_POOL_SIZE(5) 个连接，而且查询、导出、工单执行共用。
        //
        // 没有这道闸门时，第 6 个并发请求会在 getConnection() 里等满
        // connectionTimeout(10 秒) 才失败 —— 而这 10 秒它一直占着一个 Web 线程。
        // 并发一上来，Tomcat 的线程池会先于数据库被打满，现象是"整个平台打不开"。
        //
        // 有了闸门，超出的请求在<b>碰到数据库之前</b>就拿到一句人话，
        // 而不是 10 秒后一个超时的 500。**用大资源（Web 线程）去等小资源（数据库连接）
        // 是排队的经典反模式。**
        QueryConcurrencyGuard.RejectReason rejectReason =
                concurrencyGuard.tryAcquire(dto.getDataSourceId(), userId);
        if (rejectReason != QueryConcurrencyGuard.RejectReason.NONE) {
            // ⭐ 用 RejectedBeforeWorkException 而不是裸的 BusinessException：
            //    这个请求**一个资源都没碰到**，所以审计切面不该为它往主库写一条记录
            //    （见 AuditLogAspect 与类注释里那段压测数据）。
            //    对用户的表现完全不变：文案、HTTP 状态、业务 code 都一样。
            throw new RejectedBeforeWorkException(concurrencyGuard.rejectionMessage(rejectReason));
        }

        try {
            // 熔断：如果这个数据源最近连续"连不上"，就不再浪费 5 秒的建连超时去打它。
            //
            // ⚠️ 注意熔断**只认真实的基础设施失败**（连不上、建连超时、池子拿不到连接），
            // **不算"用户写错 SQL"** —— 否则用户连续写错 5 次 SQL 就能把整个数据源熔断，
            // 别人也用不了了。判断逻辑在 DbSourceCircuitBreaker 里。
            if (!circuitBreaker.allowRequest(dto.getDataSourceId())) {
                // 同理：熔断拒绝也**没有真的去连数据库**（那正是熔断的意义），
                // 所以不该产生一条审计写 —— 这也是同一个类型存在的原因。
                throw new RejectedBeforeWorkException(
                        "该数据源最近连续连接失败，已暂时熔断，请稍后重试");
            }

            QueryVO vo = doExecuteWithGuard(userId, dto);
            circuitBreaker.recordSuccess(dto.getDataSourceId());
            return vo;
        } catch (BusinessException e) {
            // 交给熔断器判断"这次失败算不算基础设施问题"（它只认真实连接故障）
            circuitBreaker.recordFailure(dto.getDataSourceId(), e);
            throw e;
        } catch (Exception e) {
            // ⭐ 这一条是第六轮补的，它修的是一个"熔断器根本不知道"的盲区：
            //
            // 数据源连不上时，**连接池是在第一次 getConnection 时才创建的**（惰性），
            // 而 HikariCP 创建池失败抛的是 PoolInitializationException（RuntimeException，
            // 不属于 java.sql 体系）。它以前会直接穿过上面那个 catch (BusinessException)，
            // 一路冒到全局兜底，后果有两个：
            //   ① 熔断器**从来没被告知**这个数据源连不上 → 连续失败永远不计数 → 永远不打开；
            //   ② 用户每次拿到的是"系统异常"，而真实原因是"这个库连不上"。
            // 实测证据见 DbSourceCircuitBreakerIntegrationTest（毫秒级对比）。
            boolean infrastructureFailure =
                    circuitBreaker.recordFailureAndClassify(dto.getDataSourceId(), e);
            if (infrastructureFailure) {
                throw new BusinessException(
                        "该数据源当前不可用（连接失败），请检查数据源配置或稍后重试", e);
            }
            throw e;
        } finally {
            // 必须在 finally 里放：中途任何一条路径抛出（数据源不存在、校验不通过、SQL 报错）
            // 都要把许可还回去，否则这个用户/数据源的许可会被永久泄漏 ——
            // 泄漏几次之后，那个人就再也执行不了查询了。
            concurrencyGuard.release(dto.getDataSourceId(), userId);
        }
    }

    /**
     * 写查询历史 —— <b>失败了也绝不影响这次执行的结论</b>。
     *
     * <h2>为什么必须包起来</h2>
     *
     * <p>审计发现：`saveHistory` 抛的是 `DataAccessException`（RuntimeException），
     * 而外层的 catch 只包 `SQLException` —— 于是平台库一抖，异常就穿到全局兜底，
     * 用户看到的是「系统异常」。**可那条 SQL 已经真的执行了**（管理员还能在查询页跑写语句），
     * 用户以为失败就会重试 → **重复改一次数据**。
     *
     * <p>历史记录是**附属品**，它没写进去不该改变"这条语句执行成功了"这个事实。
     * 失败本身要留痕：计数器 + WARN 日志（不静默）。
     */
    private void saveHistoryQuietly(Long userId, Long dataSourceId, String sql,
                                    String status, long cost, String error) {
        try {
            queryHistoryService.saveHistory(userId, dataSourceId, sql, status, cost, error);
        } catch (Exception e) {
            // 注意级别：这是**可预期的降级**（平台库抖一下就会走到这里），用 WARN 不用 ERROR，
            // 而且要能告警 —— 见 SqlPlatformMetrics.HISTORY_SAVE_FAILED
            metrics.recordHistorySaveFailed();
            log.warn("查询历史写入失败（**本次执行结果不受影响**）。dataSourceId={}, status={}",
                    dataSourceId, status, e);
        }
    }

    private QueryVO doExecuteWithGuard(Long userId, QueryDTO dto) {
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

            saveHistoryQuietly(userId, dbSource.getId(), dto.getSql(), "SUCCESS", cost, null);

            vo.setCostMs(cost);
            return vo;
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;

            metrics.recordQueryFailure(cost);

            saveHistoryQuietly(userId, dbSource.getId(), dto.getSql(), "FAILED", cost, e.getMessage());

            // ⚠️ 必须把 cause 带上：熔断器是靠"沿 cause 链找连接类异常"判断的，
            //    以前这里只传 message，于是熔断器拿到的异常链是断的 ——
            //    判定恒为"不是基础设施失败"，**熔断永远不会打开**。
            throw new BusinessException("SQL执行失败：" + e.getMessage(), e);
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
