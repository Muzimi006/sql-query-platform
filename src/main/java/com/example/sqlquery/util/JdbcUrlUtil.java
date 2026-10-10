package com.example.sqlquery.util;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;

/**
 * 拼 JDBC 连接串。
 *
 * <p>这个方法是连接串的<b>唯一出口</b>：业务库连接池（{@link ConnectionManager}）
 * 和「测试连接」（{@code DbSourceServiceImpl#testConnection}）都走它 ——
 * 所以在这里加超时参数、加新方言，一处改动就能覆盖两条链路。
 *
 * <p>改造前这里写死了 {@code jdbc:mysql://}，而前端下拉框里已经有别的数据库选项 ——
 * 选了也照样拼出一条 MySQL 连接串，没有任何地方会报错。
 * 现在改成先解析出 {@link SqlDialect}，再由方言决定前缀与参数。
 */
public class JdbcUrlUtil {

    /**
     * TCP 建连超时（毫秒）。
     *
     * <p>MySQL Connector/J 的 {@code connectTimeout} 默认是 <b>0，即不超时</b> ——
     * 实际等待时间交给操作系统决定：Windows 约 21 秒，Linux 约 130 秒。
     * 用户在「测试连接」里填一个不可达的主机时，请求就会卡这么久，
     * 而且这期间一直占着一个 Tomcat 线程，并发点几次就能把线程池耗光。
     *
     * <p>取 5 秒的理由：正常可达的数据库握手在 1 秒内完成，5 秒有足够余量；
     * 同时它比连接池的 {@code connectionTimeout}（10 秒）更短，
     * 这样「建连失败」会先于「等池超时」暴露出来，报错信息更准确。
     *
     * <p>⚠️ <b>单位是毫秒，但这不等于每个方言都按毫秒收</b>：
     * PostgreSQL 的 {@code connectTimeout} 单位是<b>秒</b>，
     * 由 {@link SqlDialect#connectionParams(int)} 负责换算。
     * 直接把毫秒值塞给 PG，等于设了一个 5000 秒的超时 —— 也就是没设。
     *
     * <p>包级可见是为了让单元测试能直接引用，避免同一个数字在两个地方各写一遍。
     */
    static final int CONNECT_TIMEOUT_MS = 5_000;

    /**
     * MySQL 侧的 {@code serverTimezone}。
     *
     * <p>它写死过一段时间（一直是 {@code Asia/Shanghai}）。写死的代价不是"不好看"：
     * 驱动靠它解释库里每一个 {@code TIMESTAMP/DATETIME}，接一个 UTC 的库时
     * <b>所有时间字段整体偏 8 小时，且不报错</b>。
     *
     * <p>现在是可配的（{@code datasource.mysql.server-timezone}），
     * 由 {@code JdbcUrlTimezoneConfig} 在启动时注入一次。
     */
    private static volatile String serverTimezone = SqlDialect.DEFAULT_SERVER_TIMEZONE;

    /**
     * 时区串的合法形状：IANA 名（{@code Asia/Shanghai}）、缩写（{@code UTC}）、
     * 带偏移的写法（{@code GMT+8}）。
     *
     * <p><b>校验不是多余的</b>：这个值会被直接拼进 JDBC URL，
     * 一个 {@code &} 就能往连接串里塞任意参数（例如 {@code &allowLoadLocalInfile=true}）。
     * 配置项多半来自运维，但"配置里能顺手改掉一条安全属性"这件事本身就不该存在。
     */
    private static final java.util.regex.Pattern TIMEZONE_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_+\\-/]{1,64}");

    private JdbcUrlUtil() {
    }

    /**
     * 设置 MySQL 时区（只应在启动时调用一次）。
     *
     * <p>非法值<b>不会抛异常</b>，只是被忽略并留一条 WARN：
     * 这是一个保护性配置，拼错它不该让应用起不来 —— 沿用默认值即可。
     */
    public static void setServerTimezone(String value) {
        if (value == null || !TIMEZONE_PATTERN.matcher(value.trim()).matches()) {
            org.slf4j.LoggerFactory.getLogger(JdbcUrlUtil.class).warn(
                    "datasource.mysql.server-timezone 的值不合法，已忽略并沿用 {}。"
                            + "只允许字母/数字/_/+/-//，且不能包含 & 或 =（那些会被拼进连接串）",
                    serverTimezone);
            return;
        }
        serverTimezone = value.trim();
    }

    /** 当前生效的 MySQL 时区（测试与日志用）。 */
    static String serverTimezone() {
        return serverTimezone;
    }

    /**
     * 拼出连接串。
     *
     * @throws com.example.sqlquery.exception.BusinessException 数据源类型为空或不被支持时
     */
    public static String build(DbSource dbSource) {
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());
        return dialect.jdbcUrl(dbSource.getHost(), dbSource.getPort(), dbSource.getDatabaseName())
                + dialect.connectionParams(CONNECT_TIMEOUT_MS, serverTimezone);
    }
}
