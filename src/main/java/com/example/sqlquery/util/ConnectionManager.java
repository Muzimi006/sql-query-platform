package com.example.sqlquery.util;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务数据源的连接池管理。
 *
 * <p>池子按数据源 id 缓存，一个数据源一个池。这里的 {@code poolCache}
 * 是<b>纯 JVM 内</b>的状态 —— 多实例部署时别的实例看不见它，
 * 这正是"{@code DbSourceCacheInvalidator}} 要广播失效"的原因（见那个类的注释）。
 */
@Component
public class ConnectionManager {

    /** 连接池上限。取 5 的理由：这是给"人工点一下执行 SQL"用的池子，不是应用自己的主库池； */
    private static final int MAX_POOL_SIZE = 5;

    private final AesUtil aesUtil;
    private final SqlPlatformMetrics metrics;

    private final ConcurrentHashMap<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    public ConnectionManager(AesUtil aesUtil, SqlPlatformMetrics metrics) {
        this.aesUtil = aesUtil;
        this.metrics = metrics;
    }

    public Connection getConnection(DbSource dbSource) throws SQLException {
        HikariDataSource dataSource = poolCache.computeIfAbsent(dbSource.getId(), id -> createDataSource(dbSource));
        return dataSource.getConnection();
    }

    /**
     * 关闭并移除某个数据源的连接池。
     *
     * <p>顺序有讲究：先摘掉指标，再关池子。
     * 反过来的话，池子关闭到指标注销之间会有一小段窗口，
     * Gauge 读到的是一个正在关闭的池子，可能抛出异常或读到瞬时归零的值。
     */
    public void evict(Long id) {
        HikariDataSource dataSource = poolCache.remove(id);
        metrics.removePoolGauges(id);

        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariDataSource createDataSource(DbSource dbSource) {
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JdbcUrlUtil.build(dbSource));

        // 显式指定驱动类名，不让 Hikari 从 URL 里猜。
        // 单方言时靠推断没问题（classpath 上只有那一个驱动），
        // 但多方言之后 MySQL 和 PostgreSQL 的驱动会同时存在 ——
        // 推断一旦猜错，报错是"driver not found"这类看不出真正原因的信息。
        config.setDriverClassName(dialect.driverClassName());

        config.setUsername(dbSource.getUsername());
        config.setPassword(aesUtil.decrypt(dbSource.getPasswordEncrypted()));
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        // 池名带上数据源 id：HikariCP 自己的日志和线程名里会带上它，
        // 出现问题时能一眼看出是哪个数据源的池子
        config.setPoolName("ds-" + dbSource.getId());

        HikariDataSource dataSource = new HikariDataSource(config);
        metrics.registerPoolGauges(dbSource.getId(), dataSource);
        return dataSource;
    }
}
