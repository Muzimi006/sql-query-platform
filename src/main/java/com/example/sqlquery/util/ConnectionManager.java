package com.example.sqlquery.util;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
public class ConnectionManager {

    /**
     * 连接池上限。取 5 的理由：这是给"人工点一下执行 SQL"用的池子，不是应用自己的主库池。
     *
     * <p>⚠️ <b>这个值有一个下游约束</b>：{@code QueryConcurrencyGuard} 的
     * {@code per-data-source-concurrency} 默认就取它 —— 因为闸门的目的是
     * "在进入执行之前就拒绝"，而不是"让请求去池子门口排队"。
     * 如果闸门许可数 <b>大于</b>池上限，多出来的那些请求还是会在
     * {@code getConnection()} 上等满 {@code connectionTimeout}，闸门就形同虚设。
     *
     * <p>所以这里是 {@code public} 的：让约束在代码里看得见，而不是靠一句注释。
     */
    public static final int MAX_POOL_SIZE = 5;

    private final AesUtil aesUtil;
    private final SqlPlatformMetrics metrics;

    /**
     * 池条目：<b>连接串指纹</b> + 池子。
     *
     * <p>为什么要存指纹：{@code poolCache} 是"按 id 缓存、条目存在就永远不重建"的，
     * 而数据源**配置是可以变的**（改密码、改库、换主机）。
     * 正常情况下"配置变了"由 Redis 广播通知所有实例失效（见 {@code DbSourceCacheInvalidator}），
     * 但**广播会失败**（Redis 不可用时），那时：
     * <ul>
     *   <li>缓存键已经全局删掉了 → 本实例重新回源，能拿到<b>新的</b> DbSource；</li>
     *   <li>可池子还是<b>旧的</b> → 继续用旧凭据连库，每个请求都失败；</li>
     *   <li>而且 <b>Redis 恢复之后也不会好</b> —— 只有重启实例。</li>
     * </ul>
     *
     * <p>指纹就是给这条路径兜底的：**配置变了就作废重建**，不依赖广播是否送达。
     */
    private record PoolEntry(String fingerprint, HikariDataSource dataSource) {
    }

    private final ConcurrentHashMap<Long, PoolEntry> poolCache = new ConcurrentHashMap<>();

    public ConnectionManager(AesUtil aesUtil, SqlPlatformMetrics metrics) {
        this.aesUtil = aesUtil;
        this.metrics = metrics;
    }

    public Connection getConnection(DbSource dbSource) throws SQLException {
        String fingerprint = fingerprintOf(dbSource);

        PoolEntry cached = poolCache.get(dbSource.getId());
        if (cached != null && !cached.fingerprint().equals(fingerprint)) {
            // ⭐ 配置变了 → 老池子作废。这是**广播丢失时唯一能兜住的地方**：
            //    没有它，本实例会一直用旧密码连库，恢复 Redis 也不会自愈。
            log.warn("数据源配置已变化，作废旧连接池并重建。dataSourceId={}", dbSource.getId());
            metrics.recordPoolRebuiltOnConfigChange();
            evict(dbSource.getId());
        }

        PoolEntry entry = poolCache.computeIfAbsent(dbSource.getId(),
                id -> new PoolEntry(fingerprint, createDataSource(dbSource)));
        return entry.dataSource().getConnection();
    }

    /**
     * 连接串指纹：配置里每一个"会影响能不能连上"的字段都要进来。
     *
     * <p>密码用的是**密文**（{@code passwordEncrypted}）—— 够用且不用解密：
     * 改密码必然导致密文变化；而且解密是有成本的，不该在每个查询上做。
     */
    static String fingerprintOf(DbSource dbSource) {
        return String.join("|",
                String.valueOf(dbSource.getType()),
                String.valueOf(dbSource.getHost()),
                String.valueOf(dbSource.getPort()),
                String.valueOf(dbSource.getDatabaseName()),
                String.valueOf(dbSource.getUsername()),
                String.valueOf(dbSource.getPasswordEncrypted()));
    }

    /**
     * 关闭并移除某个数据源的连接池。
     *
     * <p>顺序有讲究：先摘掉指标，再关池子。
     * 反过来的话，池子关闭到指标注销之间会有一小段窗口，
     * Gauge 读到的是一个正在关闭的池子，可能抛出异常或读到瞬时归零的值。
     */
    public void evict(Long id) {
        PoolEntry entry = poolCache.remove(id);
        metrics.removePoolGauges(id);

        if (entry != null) {
            entry.dataSource().close();
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
