package com.example.sqlquery.sql;

import com.example.sqlquery.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 支持的 SQL 方言。
 *
 * <h2>为什么要有这个东西</h2>
 *
 * <p>改造前整个项目<b>到处都写死了 MySQL</b>：连接串是 `jdbc:mysql://`、
 * 危险函数名单里是 {@code sleep} / {@code load_file}、系统库名单里是 {@code mysql} / {@code information_schema}。
 * 更要命的是前端下拉框里已经有一个「openGauss」选项 ——
 * <b>选了它，存下来的仍然是一条 MySQL 连接串</b>，没有任何地方会报错。
 *
 * <p>把方言相关的知识<b>全部收进这一个枚举</b>，是为了让"支持一个新数据库"
 * 变成一件可以照着做、而且不容易漏的事情：连接串前缀、驱动类名、默认端口、
 * 危险函数、系统库、专有语法，六个点必须一起补齐，少一个就是一处静默错误。
 *
 * <h2>怎么加一个新方言</h2>
 *
 * <ol>
 *   <li>在 {@code pom.xml} 加对应驱动依赖；</li>
 *   <li>在这里加一个枚举常量，把六个点都填上；</li>
 *   <li>补单元测试（至少覆盖：连接串、危险函数、系统库）；</li>
 *   <li>前端<b>不用改</b> —— 下拉框是读 `GET /api/datasource/dialects` 生成的。</li>
 * </ol>
 */
public enum SqlDialect {

    /**
     * MySQL。
     *
     * <p>危险函数名单与系统库名单沿用改造前 {@code SqlValidateUtil} 里的那套，
     * 一个字没改 —— 改造的目标是"支持更多方言"，不是"改变 MySQL 的既有策略"。
     */
    MYSQL(
            "MySQL",
            "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql",
            3306,
            // MySQL 的 $ 是合法的标识符字符（col$name 是合法列名），
            // 所以不能把 $...$ 当成字符串字面量 —— 认错了会把真正的代码当成字面量屏蔽掉
            false,
            Set.of(
                    "sleep",        // 拖慢数据库（时间盲注常用）
                    "benchmark",    // 同上，重复执行表达式
                    "load_file",    // 读服务器上的任意文件
                    "get_lock",     // 抢占命名锁，可用来制造阻塞
                    "release_lock",
                    "sys_exec",     // UDF：执行系统命令
                    "sys_eval"
            ),
            Set.of("mysql", "information_schema", "performance_schema", "sys"),
            // MySQL 的系统库必须带库名前缀才能访问，所以没有"裸表名"要额外拦
            Set.of(),
            // EXPLAIN FORMAT=JSON 里"预估扫描行数"的字段
            "rows_examined_per_scan",
            // access_type = ALL 就是全表扫描 —— 这个信号比行数本身更值得让审批人看到
            "access_type", "ALL"
    ),

    /**
     * PostgreSQL。
     *
     * <h3>危险函数与 MySQL 完全是另一套</h3>
     *
     * <p>名字一个都对不上：{@code sleep} 在 PG 里是 {@code pg_sleep}，
     * {@code load_file} 对应的是 {@code pg_read_file}。
     * <b>这就是"字符串匹配名单必须按方言拆分"的直接原因</b> ——
     * 拿 MySQL 的名单去检查 PG 的 SQL，等于什么都没检查。
     */
    POSTGRESQL(
            "PostgreSQL",
            "org.postgresql.Driver",
            "jdbc:postgresql",
            5432,
            // PG 的 $$...$$ / $tag$...$tag$ 是标准的字符串字面量语法，必须认
            true,
            Set.of(
                    "pg_sleep",                     // 拖慢数据库
                    "pg_sleep_for",
                    "pg_sleep_until",
                    "pg_read_file",                 // 读服务器文件
                    "pg_read_binary_file",
                    "pg_ls_dir",                    // 列目录
                    "pg_stat_file",                 // 看文件元信息
                    "lo_import",                    // 大对象：也能读写文件
                    "lo_export",
                    "dblink",                       // 连到别的库
                    "dblink_connect",
                    "dblink_exec",
                    "pg_execute_server_program",    // 执行系统命令
                    "pg_terminate_backend",         // 掐别人的连接
                    "pg_cancel_backend"
            ),
            Set.of("pg_catalog", "information_schema", "pg_toast"),
            /**
             * PG 有一个 MySQL 没有的性质：{@code pg_catalog} <b>默认就在 search_path 里</b>，
             * 所以 {@code select * from pg_shadow} <b>不带库名前缀也能查到</b>。
             * 只检查"库名前缀"的话，这一类会被整个漏掉。
             *
             * <p>这里只列真正敏感的几张（含口令哈希、含连接串），
             * 不去穷举整个 pg_catalog —— 那会把 {@code pg_type} 这种无害的表也一起拦掉，
             * 属于"为了安全把功能搞坏"。
             */
            Set.of("pg_shadow", "pg_authid", "pg_user_mappings", "pg_subscription"),
            // EXPLAIN (FORMAT JSON) 里"预估行数"的字段（注意 PG 的字段名里带空格）
            "Plan Rows",
            // Seq Scan = 顺序扫描 = 全表扫描
            "Node Type", "Seq Scan"
    );

    private final String displayName;
    private final String driverClassName;
    private final String jdbcUrlPrefix;
    private final int defaultPort;
    private final boolean supportsDollarQuoting;
    private final Set<String> dangerousFunctions;
    private final Set<String> systemSchemas;
    private final Set<String> dangerousTables;

    /** EXPLAIN 输出里"预估行数"的字段名。 */
    private final String explainRowsField;

    /** EXPLAIN 输出里表示"全表扫描"的字段名与取值。 */
    private final String explainFullScanField;
    private final String explainFullScanValue;

    SqlDialect(String displayName, String driverClassName, String jdbcUrlPrefix, int defaultPort,
               boolean supportsDollarQuoting, Set<String> dangerousFunctions,
               Set<String> systemSchemas, Set<String> dangerousTables,
               String explainRowsField, String explainFullScanField, String explainFullScanValue) {
        this.displayName = displayName;
        this.driverClassName = driverClassName;
        this.jdbcUrlPrefix = jdbcUrlPrefix;
        this.defaultPort = defaultPort;
        this.supportsDollarQuoting = supportsDollarQuoting;
        this.dangerousFunctions = dangerousFunctions;
        this.systemSchemas = systemSchemas;
        this.dangerousTables = dangerousTables;
        this.explainRowsField = explainRowsField;
        this.explainFullScanField = explainFullScanField;
        this.explainFullScanValue = explainFullScanValue;
    }

    /**
     * 由数据源里用户填的 {@code type} 解析出方言。
     *
     * <p><b>大小写不敏感</b>：库里既有 {@code mysql} 也有 {@code MySQL}（实测）。
     *
     * <p><b>认不出来就报错，不猜</b>：猜错的后果是"用 MySQL 的连接串去连 PostgreSQL"，
     * 那种失败很难从报错信息里看出真正原因。宁可在这里明确拒绝。
     */
    public static SqlDialect fromType(String type) {
        if (type == null || type.isBlank()) {
            throw new BusinessException("数据源类型不能为空");
        }

        String normalized = type.trim().toLowerCase(Locale.ROOT);
        for (SqlDialect dialect : values()) {
            if (dialect.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || dialect.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return dialect;
            }
        }
        // 常见的别名，避免用户填 postgres / pg 就被拒
        if ("postgres".equals(normalized) || "pg".equals(normalized)) {
            return POSTGRESQL;
        }

        throw new BusinessException("暂不支持的数据源类型：" + type
                + "（目前支持 " + supportedNames() + "）");
    }

    /**
     * 枚举里的一项，供前端下拉框使用。
     *
     * <p>做成接口下发而不是让前端写死一份清单：两边各写一份的话，
     * 后端加了一个方言而前端没加，用户根本选不到它 ——
     * 而且不会有任何报错，只是"这个功能好像不存在"。
     */
    public record Option(String value, String label, int defaultPort) {
    }

    /** 支持的方言列表，顺序与枚举声明一致。 */
    public static java.util.List<Option> options() {
        java.util.List<Option> options = new java.util.ArrayList<>();
        for (SqlDialect dialect : values()) {
            options.add(new Option(dialect.name(), dialect.displayName, dialect.defaultPort));
        }
        return options;
    }

    /** 支持的方言名，用于拼报错信息。 */
    public static String supportedNames() {
        StringBuilder names = new StringBuilder();
        for (SqlDialect dialect : values()) {
            if (names.length() > 0) {
                names.append(" / ");
            }
            names.append(dialect.displayName);
        }
        return names.toString();
    }

    /**
     * 拼 JDBC 连接串。
     *
     * <p>库名可以留空：MySQL 会连到"没有默认库"的状态，PostgreSQL 会退化成用用户名当库名 ——
     * 两种行为都由驱动决定，这里不额外处理。
     */
    public String jdbcUrl(String host, Integer port, String databaseName) {
        StringBuilder url = new StringBuilder(jdbcUrlPrefix).append("://").append(host);
        if (port != null) {
            url.append(':').append(port);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            url.append('/').append(databaseName);
        }
        return url.toString();
    }

    /**
     * 连接参数。
     *
     * <h3>⚠️ 两个方言的参数名和单位都不一样</h3>
     *
     * <ul>
     *   <li>{@code connectTimeout} 这个参数名<b>两边都有，但单位不同</b>：
     *       MySQL 是<b>毫秒</b>，PostgreSQL 是<b>秒</b>。
     *       把毫秒值直接塞给 PG，等于设了一个 5000 秒的超时 —— 也就是没设。</li>
     *   <li>MySQL 需要显式关掉 SSL 并允许公钥检索，PG 有自己的 {@code sslmode}。</li>
     * </ul>
     *
     * @param connectTimeoutMs 统一的建连超时（毫秒）；各方言自行换算成自己的单位
     */
    public String connectionParams(int connectTimeoutMs) {
        return connectionParams(connectTimeoutMs, DEFAULT_SERVER_TIMEZONE);
    }

    /**
     * MySQL 驱动的 {@code serverTimezone} 默认值。
     *
     * <p>它决定驱动把库里的 {@code TIMESTAMP/DATETIME} 当**哪个时区**来解释。
     * 写死成 {@code Asia/Shanghai} 对国内部署是对的，但对接一个 UTC 的库时
     * <b>每个时间字段都会整体偏 8 小时</b> —— 而且不会报任何错。
     * 所以它可配（{@code datasource.mysql.server-timezone}），这里只是默认值。
     */
    public static final String DEFAULT_SERVER_TIMEZONE = "Asia/Shanghai";

    /**
     * 连接参数（可指定 MySQL 时区）。
     *
     * @param connectTimeoutMs 建连超时（毫秒）
     * @param serverTimezone   MySQL 侧时区；PostgreSQL 不使用这个参数（PG 由服务端
     *                         的 {@code TimeZone} 决定，连接串里没有对应开关）
     */
    public String connectionParams(int connectTimeoutMs, String serverTimezone) {
        return switch (this) {
            // ⚠️ socketTimeout 不能省：MySQL 驱动默认 0 = 永不超时。
            //    业务库 TCP 可达但不再响应时，查询会永久阻塞 —— 而这个池子只有 5 条连接，
            //    占满之后整个数据源就废了（并发闸门也救不了：许可被挂住的线程带走了）。
            //    60000 给得比 setQueryTimeout(30/300s) 宽松，正常慢查询不会误杀，
            //    它兜的是"连接层面已经死了"那种情况。
            case MYSQL -> "?useSSL=false"
                    + "&serverTimezone=" + serverTimezone
                    + "&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=" + connectTimeoutMs
                    + "&socketTimeout=60000";
            case POSTGRESQL -> {
                // 向上取整到秒：4999ms 也要给够 5 秒，不能因为整除变成 4 秒
                int seconds = Math.max(1, (connectTimeoutMs + 999) / 1000);
                // PG 的 socketTimeout 单位是**秒**（和 MySQL 的毫秒不同），所以同样要换算
                yield "?connectTimeout=" + seconds
                        + "&socketTimeout=60"
                        + "&sslmode=prefer"
                        + "&ApplicationName=sql-query-platform";
            }
        };
    }

    public String displayName() {
        return displayName;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public int defaultPort() {
        return defaultPort;
    }

/**
     * 引用一个标识符（表名 / 列名）。
     *
     * <p>⚠️ MySQL 用反引号、PostgreSQL 用双引号 —— 这是两种方言最常见的
     * "看起来一样、其实不一样"的地方之一。写死一种，另一条链路在遇到保留字
     * 列名（比如 {@code order}、{@code status}）时就会炸。
     *
     * <p>⚠️ 引用只能防"保留字冲突"，<b>防不了"把恶意内容拼进 SQL"</b> ——
     * 后者要靠调用方对标识符字符集做白名单校验（见 {@code SqlUndoRecorder}）。
     */
    public String quoteIdentifier(String identifier) {
        if (this == MYSQL) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 该方言是否支持 {@code $$...$$} / {@code $tag$...$tag$} 形式的字符串字面量。 */
    public boolean supportsDollarQuoting() {
        return supportsDollarQuoting;
    }

    /** 该方言下需要拦截的函数名（小写）。 */
    public Set<String> dangerousFunctions() {
        return dangerousFunctions;
    }

    /** 该方言下需要拦截的系统库名（小写）。 */
    public Set<String> systemSchemas() {
        return systemSchemas;
    }

    /** 不带库名前缀也要拦截的表名（小写）。目前只有 PG 需要，原因见上面的注释。 */
    public Set<String> dangerousTables() {
        return dangerousTables;
    }

    /**
     * 拼出"只估算、不执行"的 EXPLAIN 语句。
     *
     * <p>⚠️ 两个方言的写法不同，而且属于"看起来一样、其实不一样"那一类：
     * <pre>
     *   MySQL      : EXPLAIN FORMAT=JSON UPDATE ...
     *   PostgreSQL : EXPLAIN (FORMAT JSON) UPDATE ...
     * </pre>
     *
     * <p>两者对<b>写语句</b>都只生成执行计划、<b>不真的执行</b>
     * （除非显式加 {@code ANALYZE}），所以拿它给审批人看影响面是安全的。
     */
    public String explainSql(String sql) {
        return switch (this) {
            case MYSQL -> "EXPLAIN FORMAT=JSON " + sql;
            case POSTGRESQL -> "EXPLAIN (FORMAT JSON) " + sql;
        };
    }

    public String explainRowsField() {
        return explainRowsField;
    }

    public String explainFullScanField() {
        return explainFullScanField;
    }

    public String explainFullScanValue() {
        return explainFullScanValue;
    }
}
