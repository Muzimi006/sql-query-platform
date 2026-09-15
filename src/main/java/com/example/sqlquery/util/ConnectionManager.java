package com.example.sqlquery.util;

import com.example.sqlquery.entity.DbSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionManager {

    private final AesUtil aesUtil;
    private final ConcurrentHashMap<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();

    public ConnectionManager(AesUtil aesUtil) {
        this.aesUtil = aesUtil;
    }

    public Connection getConnection(DbSource dbSource) throws SQLException {
        HikariDataSource dataSource = poolCache.computeIfAbsent(dbSource.getId(), id -> createDataSource(dbSource));
        return dataSource.getConnection();
    }

    public void evict(Long id) {
        HikariDataSource dataSource = poolCache.remove(id);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariDataSource createDataSource(DbSource dbSource) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JdbcUrlUtil.build(dbSource));
        config.setUsername(dbSource.getUsername());
        config.setPassword(aesUtil.decrypt(dbSource.getPasswordEncrypted()));
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        return new HikariDataSource(config);
    }
}
