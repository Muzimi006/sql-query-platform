package com.example.sqlquery.service;

import com.example.sqlquery.common.UndoStatus;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.util.ConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 执行工单的写语句，**顺便把「前镜像 + 反向 SQL」留下来**。
 *
 * <h2>它补的是哪一个洞（L6）</h2>
 *
 * <p>在这之前，平台对一条已经生效的 {@code UPDATE} 能说的只有两句话：
 * {@code affected_rows} 是多少、{@code error_message} 是什么。
 * <b>回滚依据一点都没留</b> —— 而这是一个专门给写语句做审批的平台，
 * 一次批错的代价原本是不可逆的。
 *
 * <h2>⭐ 最关键的一个设计决定：不留「一键回滚」接口</h2>
 *
 * <p>反向 SQL 生成之后，<b>不由本类执行</b>，而是存进 {@code sql_review_undo}，
 * 由人核对后<b>作为一条新工单提交，走完全相同的审批路径</b>。
 *
 * <p>理由是这个项目里反复出现的那条线：<b>回滚本身也是一条写语句。</b>
 * 如果给它开一个直通接口，等于在「所有写语句都必须有人看过」这条规矩上
 * 凿一个洞 —— 而这个洞偏偏开在最容易出事的时刻（刚改错了数据、人最急）。
 * 更实际的是：反向 SQL 会<b>再改一次数据</b>，它自己也需要被审。
 *
 * <h2>它怎么取像（以及为什么必须在一个事务里）</h2>
 *
 * <ol>
 *   <li>把 SQL 解析成 AST，只接受<b>单表 {@code UPDATE} / {@code DELETE}</b>；</li>
 *   <li>查目标表的<b>主键</b>（没有主键就不留存 —— 没有主键就没法「定位到那几行」）；</li>
 *   <li>{@code SELECT * FROM t WHERE <原样的 where> LIMIT n+1 FOR UPDATE} ——
 *       多读一行是为了判断「是不是超了上限」（和查询链路判断截断是同一个技巧）；
 *       {@code FOR UPDATE} 是为了<b>把这几行锁住</b>，让「取像」和「改动」之间
 *       不会有别人插进来；</li>
 *   <li>执行原语句；</li>
 *   <li>按<b>主键</b>重新查出这几行作为后镜像
 *       （不能再用原来的 where —— 比如 {@code SET status='done' WHERE status='pending'}
 *       改完就再也匹配不到自己了）；</li>
 *   <li>提交。整个过程在<b>一个事务</b>里，所以「取了像但没改」和「改了但没取像」都不会发生。</li>
 * </ol>
 *
 * <h2>⚠️ 已知的能力边界（都记成 SKIPPED，不会被当成故障）</h2>
 *
 * <ul>
 *   <li><b>多表 UPDATE、DDL、INSERT</b> —— 形态不支持生成反向 SQL；</li>
 *   <li><b>目标表没有主键</b> —— 无法定位行；</li>
 *   <li><b>改动行数超过留存上限</b>（{@code review.undo.max-rows}，默认 200）——
 *       把 10 万行塞进 JSON 会把平台库自己撑爆。<b>这种情况仍然会执行</b>
 *       （工单已经批过了，不能因为「留存不下来」就拒绝执行），
 *       但会记一条 SKIPPED 说明「这次没有回滚依据」；</li>
 *   <li><b>含有二进制列</b> —— 无法生成字面量，同样记 SKIPPED。</li>
 * </ul>
 *
 * <p>⚠️ 而<b>取像过程本身的意外失败</b>（解析异常、元数据读不到）走的是 FAILED：
 * 那时会回滚并让整个执行失败（工单标 FAILED、数据不变）。
 * 理由是：取像失败意味着我们<b>说不清现在是什么状态</b>，
 * 而「在说不清状态的时候写数据」正是这个项目一直在避免的事。
 * <b>要么带着可回滚依据成功，要么什么都不做。</b>
 */
@Slf4j
@Component
public class SqlUndoRecorder {

    /**
     * 标识符白名单：字母或下划线开头，可带一个 {@code schema.} 前缀。
     *
     * <p>⚠️ 表名来自 AST（已经过 {@code SqlValidateUtil} 的系统库校验），
     * 但它最终会被<b>拼进 SQL 字符串</b> —— 所以这里再来一道形状校验。
     * {@code quoteIdentifier} 负责防保留字冲突，这一道负责防拼接。
     */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    /**
     * 取像 / 写入的单语句超时。
     *
     * <p>刻意复用消费者那个属性名（{@code review.query-timeout-seconds}）：
     * 同一件事配两个地方，早晚会有人只改一个。
     */
    @Value("${review.query-timeout-seconds:120}")
    private int statementTimeoutSeconds = 120;

    /**
     * 一次最多留存多少行前镜像。
     *
     * <p>超过就记 SKIPPED（不是错误）。默认 200 是权衡的结果：
     * 一条人工审批的写语句，真正会改到几百行以上时，
     * <b>反向 SQL 本身也不再是一份「人能核对」的东西</b> ——
     * 那时候正确的工具是备份恢复，不是这个功能。
     */
    @Value("${review.undo.max-rows:200}")
    private int maxRows = 200;

    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    public SqlUndoRecorder(ConnectionManager connectionManager, ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    /** 一次执行的结果：影响行数 + 留存情况。 */
    public record WriteResult(long affectedRows, UndoCapture capture) {
    }

    /**
     * 留存结果。
     *
     * @param status    见 {@link UndoStatus}
     * @param reason    没留存下来时的原因（要原样展示给管理员，所以是人话）
     * @param tableName 被改动的表
     * @param rowCount  留存了多少行
     */
    public record UndoCapture(String status, String reason, String tableName, Integer rowCount,
                              String beforeJson, String afterJson, String inverseSql) {

        public static UndoCapture skipped(String reason) {
            return new UndoCapture(UndoStatus.SKIPPED, reason, null, null, null, null, null);
        }

        /**
         * 取像失败（本次已回滚、未执行任何语句）。
         *
         * <p>⚠️ 这个工厂是补上的：在这之前 {@link UndoStatus#FAILED} **从来没有任何地方写入过**
         * —— 三态在数据里实际只有两态。后果是：任何按 {@code capture_status='FAILED'}
         * 写的巡检/告警**静默地永远不会触发**，而文档与表注释都描述了这个状态。
         */
        public static UndoCapture failed(String reason) {
            return new UndoCapture(UndoStatus.FAILED, reason, null, null, null, null, null);
        }
    }

    /**
     * 「这次留不下来」—— 会回滚、重新不带留存地执行一遍，并记一条 SKIPPED。
     *
     * <p>⚠️ 它和「取像出错」必须分开：行数超上限、表里有二进制列都是
     * <b>能力边界</b>（正常现象），不该报警；而取像抛异常是要人去看的。
     */
    static class SkipCaptureException extends RuntimeException {
        SkipCaptureException(String message) {
            super(message);
        }
    }

    /**
     * 执行写语句并留存前镜像。
     *
     * <p>取像与执行在<b>同一个事务</b>里 —— 这是这个方法存在的全部理由。
     * 分成两个事务的话，「取完像」和「真正改」之间的窗口里别人改了什么，
     * 反向 SQL 就会把那部分也一起还原，变成一次<b>错误的回滚</b>。
     */
    public WriteResult executeWithUndo(DbSource dbSource, String sql) throws SQLException {
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());

        try (Connection connection = connectionManager.getConnection(dbSource)) {
            connection.setAutoCommit(false);
            try {
                ParsedWrite parsed = parseWritable(sql);
                if (parsed == null) {
                    long affected = executeWrite(connection, sql);
                    connection.commit();
                    return new WriteResult(affected, UndoCapture.skipped(
                            "这条语句不是单表 UPDATE/DELETE（多表更新、DDL、INSERT 都无法生成反向 SQL）"));
                }

                List<String> primaryKeys = primaryKeys(connection, dialect, parsed.plainTableName());
                if (primaryKeys.isEmpty()) {
                    long affected = executeWrite(connection, sql);
                    connection.commit();
                    return new WriteResult(affected, UndoCapture.skipped(
                            "目标表 " + parsed.tableName() + " 没有主键，无法定位被改动的行"));
                }

                // ---- ① 前镜像（FOR UPDATE 把这几行锁住）----
                List<Map<String, Object>> before = selectForUpdate(connection, dialect, parsed);
                if (before.size() > maxRows) {
                    throw new SkipCaptureException("改动的行数超过留存上限 " + maxRows
                            + " 行，未留存前镜像（回滚需要依赖数据库备份或 binlog）");
                }
                if (hasUnsupportedValue(before)) {
                    throw new SkipCaptureException("目标表含有二进制 / 大对象列，无法生成反向 SQL 字面量");
                }

                // ---- ② 执行 ----
                long affected = executeWrite(connection, sql);

                // ---- ③ 后镜像：必须按主键重查（原 where 可能已经匹配不到自己）----
                List<Map<String, Object>> after = selectByPrimaryKeys(
                        connection, dialect, parsed, primaryKeys, before);

                String inverseSql = buildInverseSql(parsed, dialect, primaryKeys, before, after);

                connection.commit();

                UndoCapture capture = new UndoCapture(
                        UndoStatus.CAPTURED,
                        inverseSql.isEmpty() ? "没有行被改动，无需回滚" : null,
                        parsed.tableName(),
                        before.size(),
                        toJson(before),
                        toJson(after),
                        inverseSql);
                return new WriteResult(affected, capture);

            } catch (SkipCaptureException e) {
                // 超上限：这次留不下来，但**语句仍然要执行**（工单已经批过了）
                connection.rollback();
                long affected = executeWrite(connection, sql);
                connection.commit();
                log.warn("工单写语句未留存前镜像（超上限），语句已执行。sql={}", sql);
                return new WriteResult(affected, UndoCapture.skipped(e.getMessage()));
            } catch (SQLException e) {
                // 执行本身的失败（语法、约束、超时）—— 原样抛给上层，和改造前的行为一致
                safeRollback(connection);
                throw e;
            } catch (RuntimeException e) {
                // 取像过程中的意外错误：回滚，**不做这次写入**
                safeRollback(connection);
                log.error("工单写语句取像失败，已回滚，未执行。sql={}", sql, e);
                throw e;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    // ------------------------------------------------------------------
    // SQL 解析
    // ------------------------------------------------------------------

    /**
     * 这条语句是否动了不止一张表。
     *
     * <p>⚠️ JOIN 和 {@code getTables()} 都要看：MySQL 的多表 DELETE 有<b>两种</b>写法
     * （{@code DELETE t1 FROM t1 JOIN t2 ...} 与 {@code DELETE t1, t2 FROM ...}），
     * 它们落在不同的字段上，只查一个会漏。
     */
    private static boolean hasMultipleTables(List<?> joins, List<?> tables) {
        if (joins != null && !joins.isEmpty()) {
            return true;
        }
        return tables != null && tables.size() > 1;
    }

    /**
     * 把 SQL 解析成「可留存」的形态；不是单表 UPDATE/DELETE 时返回 {@code null}。
     *
     * <p>解析失败也只返回 {@code null}（交给数据库去报真正的语法错误）——
     * 我们不该在这里抢先给出一个语义更差的错误。
     */
    ParsedWrite parseWritable(String sql) {
        try {
            // ⚠️ 用 Object 接，不引 net.sf.jsqlparser.statement.Statement ——
            // 它和 java.sql.Statement **同名**，两个 single-type-import 会直接编译不过。
            // 这里只需要 instanceof 判断，用不上那个类型。
            Object statement = CCJSqlParserUtil.parse(sql);
            Table table;
            String whereSql;
            boolean delete;

            if (statement instanceof Update update) {
                // ⚠️ 多表 UPDATE（UPDATE t1 JOIN t2 SET ...）里的列可能来自另一张表，
                //    拿单张表去取像会取到错的（或者根本取不到）行
                if (hasMultipleTables(update.getJoins(), update.getStartJoins())) {
                    log.debug("多表 UPDATE，跳过留存");
                    return null;
                }
                table = update.getTable();
                whereSql = update.getWhere() == null ? null : update.getWhere().toString();
                delete = false;
            } else if (statement instanceof Delete del) {
                // ⚠️ 这里踩过一次：{@code DELETE t1 FROM t1 JOIN t2 ...} 的 getTable()
                //    **不是 null**，它返回 t1 —— 也就是说"多表 DELETE"骗过了"table == null"
                //    这个判据，会被当成单表 DELETE 处理，然后生成一条**语义错误的反向 SQL**。
                //    正确的判据是看 join 和 getTables()。
                if (hasMultipleTables(del.getJoins(), del.getTables())) {
                    log.debug("多表 DELETE，跳过留存");
                    return null;
                }
                table = del.getTable();
                if (table == null) {
                    return null;
                }
                whereSql = del.getWhere() == null ? null : del.getWhere().toString();
                delete = true;
            } else {
                return null;
            }

            String name = table.getFullyQualifiedName();
            if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
                log.warn("表名不是可安全拼接的形状，跳过留存。table={}", name);
                return null;
            }

            // ⚠️ 没有 WHERE 的 UPDATE/DELETE 是**全表操作**：它也能「留存」，
            //    但几乎必然超过上限。这里不特判，让它自然走到 SkipCaptureException ——
            //    那条路径会把原因写清楚。
            String alias = table.getAlias() == null ? null : table.getAlias().getName();
            return new ParsedWrite(name, table.getName(), alias, whereSql, delete);
        } catch (Exception e) {
            log.debug("SQL 无法解析为可留存形态，跳过留存。sql={}", sql);
            return null;
        }
    }

    /**
     * 一条「形态上可留存」的写语句。
     *
     * @param tableName      可含 schema 的表名（会拼进 SQL，已过形状校验）
     * @param plainTableName 不带 schema 的表名（查主键元数据要用）
     * @param alias          表别名（可能为 null）
     *                       —— ⚠️ 它必须留着：{@code UPDATE t_user u SET ... WHERE u.id = 1}
     *                       里的 WHERE 引用的是别名，取像时若写成 {@code FROM t_user WHERE u.id = 1}
     *                       数据库会直接报 unknown column/alias，整次留存失败
     * @param whereSql       原样的 WHERE（为 null 表示全表操作）
     * @param delete         是 DELETE 还是 UPDATE
     */
    record ParsedWrite(String tableName, String plainTableName, String alias, String whereSql,
                       boolean delete) {

        /** 取像时用的 FROM 片段（带上别名）。 */
        String fromClause(SqlDialect dialect) {
            String quoted = dialect.quoteIdentifier(tableName);
            return alias == null || alias.isBlank() ? quoted : quoted + " " + alias;
        }
    }

    // ------------------------------------------------------------------
    // 取像
    // ------------------------------------------------------------------

    /**
     * 前镜像：{@code SELECT * FROM t WHERE <原样的 where> LIMIT n+1 FOR UPDATE}。
     *
     * <p>⚠️ {@code FOR UPDATE} 不能省。省掉它，我们读到的就只是一个<b>快照</b>，
     * 而真正被改的可能已经是别的值了 —— 于是反向 SQL 会把「回滚」变成「改错」。
     */
    private List<Map<String, Object>> selectForUpdate(Connection connection, SqlDialect dialect,
                                                      ParsedWrite parsed) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(parsed.fromClause(dialect));
        if (parsed.whereSql() != null) {
            sql.append(" WHERE ").append(parsed.whereSql());
        }
        // 多读一行：用来判断「是不是超了上限」（和查询链路判断截断同一个技巧）
        sql.append(" LIMIT ").append(maxRows + 1).append(" FOR UPDATE");
        return queryRows(connection, sql.toString());
    }

    /**
     * 后镜像：按<b>主键</b>重新查同一批行。
     *
     * <p>⚠️ 不能再用原来的 WHERE —— {@code SET status='done' WHERE status='pending'}
     * 改完就再也匹配不到自己了，后镜像会变成空。
     */
    private List<Map<String, Object>> selectByPrimaryKeys(Connection connection, SqlDialect dialect,
                                                          ParsedWrite parsed, List<String> pkColumns,
                                                          List<Map<String, Object>> before) throws SQLException {
        if (before.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(dialect.quoteIdentifier(parsed.tableName()))
                .append(" WHERE ");

        List<Object> params = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(");
            for (int j = 0; j < pkColumns.size(); j++) {
                if (j > 0) {
                    sql.append(" AND ");
                }
                sql.append(dialect.quoteIdentifier(pkColumns.get(j))).append(" = ?");
                params.add(before.get(i).get(pkColumns.get(j)));
            }
            sql.append(")");
        }

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(statementTimeoutSeconds);
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return readRows(rs);
            }
        }
    }

    /**
     * 目标表的主键列（按 KEY_SEQ 排序）。
     *
     * <p>⚠️ 顺序必须稳定：复合主键的列顺序会同时出现在后镜像的 WHERE 和反向 SQL 里，
     * 顺序一变，两边就对不上。
     */
    List<String> primaryKeys(Connection connection, SqlDialect dialect, String plainTableName)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        // MySQL 的库名走 catalog、schema 传 null；PostgreSQL 相反。
        // 传错的表现是「查不到主键」→ 静默降级成 SKIPPED，所以这里要按方言分开处理。
        String catalog = dialect == SqlDialect.MYSQL ? connection.getCatalog() : null;
        String schema = dialect == SqlDialect.MYSQL ? null : safeSchema(connection);

        java.util.TreeMap<Short, String> ordered = new java.util.TreeMap<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, plainTableName)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (Exception e) {
            // ⚠️ 这里原来是**空 catch**（连日志都没有）—— 它不是「降级」，而是**把失败的原因报错了**：
            //    schema 取不到 → getPrimaryKeys 查不到主键 → 落库的 SKIPPED 原因写成
            //    「目标表 X 没有主键」。审批人会照着这个**错误的原因**排查，而真实原因零痕迹。
            //    现在改成：记一条 WARN，并抛 SkipCaptureException 让原因如实落库。
            log.warn("读取数据库 schema 失败，无法确认主键。cause={}", e.toString());
            throw new SkipCaptureException("读取数据库元数据失败，无法确认主键：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> queryRows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(statementTimeoutSeconds);
            try (ResultSet rs = statement.executeQuery(sql)) {
                return readRows(rs);
            }
        }
    }

    /** 用 {@link LinkedHashMap}：JSON 里的列顺序必须稳定，否则两次 diff 会看到无意义的差异。 */
    private static List<Map<String, Object>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(metaData.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 反向 SQL
    // ------------------------------------------------------------------

    /**
     * 由前镜像生成反向 SQL。
     *
     * <ul>
     *   <li>{@code UPDATE} → 对每一行生成一条 {@code UPDATE ... SET <只含被改动的列> WHERE <主键>}；</li>
     *   <li>{@code DELETE} → 对每一行生成一条 {@code INSERT INTO ... VALUES ...}；</li>
     *   <li>某一行<b>没有任何列发生变化</b> → 跳过它（反向 SQL 里不需要出现）；</li>
     *   <li>前镜像为空（一行都没改到） → 返回空串，调用方会记一条「无需回滚」。</li>
     * </ul>
     *
     * <p>⚠️ <b>只生成、不执行。</b>它要由人核对后作为一条新工单提交 ——
     * 理由见类注释（回滚本身也是一条写语句，必须走同样的审批路径）。
     */
    String buildInverseSql(ParsedWrite parsed, SqlDialect dialect, List<String> pkColumns,
                           List<Map<String, Object>> before, List<Map<String, Object>> after) {
        if (before.isEmpty()) {
            return "";
        }

        Map<String, Map<String, Object>> afterByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : after) {
            afterByKey.put(keyOf(row, pkColumns), row);
        }

        StringBuilder out = new StringBuilder();
        for (Map<String, Object> row : before) {
            String pkPredicate = pkPredicate(dialect, pkColumns, row);

            if (parsed.delete()) {
                out.append("INSERT INTO ").append(dialect.quoteIdentifier(parsed.tableName()))
                        .append(" (").append(joinColumns(dialect, row)).append(")")
                        .append(" VALUES (").append(joinLiterals(row)).append(");")
                        .append(System.lineSeparator());
                continue;
            }

            Map<String, Object> afterRow = afterByKey.get(keyOf(row, pkColumns));
            if (afterRow == null) {
                // UPDATE 之后这一行不见了 —— 只可能是别的事务干的，交给人确认
                out.append("-- ⚠️ 主键 ").append(pkPredicate)
                        .append(" 的行在执行后已不存在，无法自动生成反向语句，需人工确认")
                        .append(System.lineSeparator());
                continue;
            }

            List<String> assignments = new ArrayList<>();
            for (Map.Entry<String, Object> column : row.entrySet()) {
                Object now = afterRow.get(column.getKey());
                if (!Objects.equals(column.getValue(), now)) {
                    assignments.add(dialect.quoteIdentifier(column.getKey())
                            + " = " + literal(column.getValue()));
                }
            }
            if (assignments.isEmpty()) {
                // 这一行被 WHERE 命中但值没变，不需要还原
                continue;
            }

            out.append("UPDATE ").append(dialect.quoteIdentifier(parsed.tableName()))
                    .append(" SET ").append(String.join(", ", assignments))
                    .append(" WHERE ").append(pkPredicate).append(";")
                    .append(System.lineSeparator());
        }

        return out.toString().trim();
    }

    private static String joinColumns(SqlDialect dialect, Map<String, Object> row) {
        List<String> columns = new ArrayList<>();
        for (String column : row.keySet()) {
            columns.add(dialect.quoteIdentifier(column));
        }
        return String.join(", ", columns);
    }

    private static String joinLiterals(Map<String, Object> row) {
        List<String> values = new ArrayList<>();
        for (Object value : row.values()) {
            values.add(literal(value));
        }
        return String.join(", ", values);
    }

    private static String pkPredicate(SqlDialect dialect, List<String> pkColumns, Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        for (String column : pkColumns) {
            parts.add(dialect.quoteIdentifier(column) + " = " + literal(row.get(column)));
        }
        return String.join(" AND ", parts);
    }

    /** 行标识：主键值拼起来，用来把前后镜像对上是同一行。 */
    private static String keyOf(Map<String, Object> row, List<String> pkColumns) {
        StringBuilder key = new StringBuilder();
        for (String column : pkColumns) {
            key.append(row.get(column)).append('\u0001');
        }
        return key.toString();
    }

    /**
     * 把一个 JDBC 值写成 SQL 字面量。
     *
     * <p>⚠️ 只处理「能安全写成字面量」的类型；遇到二进制等类型时，
     * 调用前就已经把这次留存降级成 SKIPPED 了（见 {@link #hasUnsupportedValue}）。
     * 这里再兜一层：真碰上了就抛出去，绝不生成一条<b>看起来对、实际错了</b>的反向 SQL。
     */
    static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] || value instanceof java.sql.Blob || value instanceof java.sql.Clob) {
            throw new IllegalStateException("二进制列无法写成 SQL 字面量：" + value.getClass().getName());
        }
        // ⚠️ 反斜杠必须**先**转义，再转义单引号（顺序反了会把刚加的反斜杠又转义一遍）。
        //
        // 为什么不能只转义单引号：MySQL 的字符串字面量里反斜杠本身是转义字符，
        // 于是值 'a\b'（一个反斜杠）会被读成 a + 退格(0x08) —— 实测：
        //     HEX('a\b') = 6108（长度 2），而正确形式 HEX('a\\b') = 615C62（长度 3）
        // 后果不是报错，而是**回滚把数据还原成错误的值**，而且终端里退格会覆盖字符、
        // 人工核对时肉眼看不出来。
        String escaped = value.toString().replace("\\", "\\\\").replace("'", "''");
        return "'" + escaped + "'";
    }

    /** 前镜像里只要有一列是二进制/大对象，整次留存就降级为 SKIPPED。 */
    static boolean hasUnsupportedValue(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                if (value instanceof byte[] || value instanceof java.sql.Blob
                        || value instanceof java.sql.Clob || value instanceof java.sql.Array) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private long executeWrite(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(statementTimeoutSeconds);
            return statement.executeUpdate(sql);
        }
    }

    private String toJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            // 镜像已经拿到了，只是序列化失败 —— 不该让整次执行失败
            log.warn("镜像序列化失败，将只保留反向 SQL。rows={}", rows.size(), e);
            return null;
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败", e);
        }
    }

    /**
     * 把 autoCommit 还原成 true。
     *
     * <p>连接是<b>池化复用</b>的：不还原的话，这条连接回到池子里还是「手动提交」状态，
     * 下一个借到它的请求写的所有东西都会悬在一个没人提交的事务里 ——
     * 表现为「偶发地丢数据」，而且极难定位。
     * （HikariCP 自己也会在归还时重置，但那是第二道保险，不是第一道。）
     */
    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("还原 autoCommit 失败", e);
        }
    }
}
