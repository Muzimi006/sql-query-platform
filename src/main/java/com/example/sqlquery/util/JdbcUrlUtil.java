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

    private JdbcUrlUtil() {
    }

    /**
     * 拼出连接串。
     *
     * @throws com.example.sqlquery.exception.BusinessException 数据源类型为空或不被支持时
     */
    public static String build(DbSource dbSource) {
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());
        return dialect.jdbcUrl(dbSource.getHost(), dbSource.getPort(), dbSource.getDatabaseName())
                + dialect.connectionParams(CONNECT_TIMEOUT_MS);
    }
}
