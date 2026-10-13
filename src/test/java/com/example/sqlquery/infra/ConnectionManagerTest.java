package com.example.sqlquery.infra;

import com.example.sqlquery.entity.DbSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 连接池缓存条目的<b>连接串指纹</b>。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code poolCache} 是"按数据源 id 缓存、条目存在就永远不重建"的。
 * 正常情况下"配置变了"由 Redis 广播通知各实例失效，
 * 但<b>广播会失败</b>（Redis 不可用）—— 那时本实例会永远拿旧凭据连库，
 * 每个请求都失败，而且 <b>Redis 恢复之后也不会自愈</b>。
 *
 * <p>指纹就是给这条路兜底的：配置变了就作废重建。
 * 所以它<b>必须覆盖每一个影响能否连上的字段</b> ——
 * 少一个，就有一类"配置改了但池子不重建"漏网。
 */
class ConnectionManagerTest {

    private static DbSource source(String type, String host, Integer port,
                                   String database, String username, String encryptedPassword) {
        DbSource dbSource = new DbSource();
        dbSource.setId(1L);
        dbSource.setType(type);
        dbSource.setHost(host);
        dbSource.setPort(port);
        dbSource.setDatabaseName(database);
        dbSource.setUsername(username);
        dbSource.setPasswordEncrypted(encryptedPassword);
        return dbSource;
    }

    private static DbSource base() {
        return source("MySQL", "127.0.0.1", 3306, "shop", "root", "ENC-V1");
    }

    @Test
    @DisplayName("配置完全相同的两个数据源，指纹必须相同（否则每次查询都重建池子）")
    void sameConfigShouldProduceSameFingerprint() {
        assertEquals(ConnectionManager.fingerprintOf(base()), ConnectionManager.fingerprintOf(base()));
    }

    @Test
    @DisplayName("⭐ 每一个影响连接的字段变了，指纹都必须跟着变")
    void everyConnectionFieldShouldAffectFingerprint() {
        String original = ConnectionManager.fingerprintOf(base());

        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("PostgreSQL", "127.0.0.1", 3306, "shop", "root", "ENC-V1")), "方言变了");
        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("MySQL", "10.0.0.9", 3306, "shop", "root", "ENC-V1")), "主机变了");
        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("MySQL", "127.0.0.1", 3307, "shop", "root", "ENC-V1")), "端口变了");
        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("MySQL", "127.0.0.1", 3306, "other_db", "root", "ENC-V1")), "库名变了");
        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("MySQL", "127.0.0.1", 3306, "shop", "app_user", "ENC-V1")), "用户名变了");
        // ⭐ 密码最要紧："改了密码但别的实例还在用旧池子"正是这条兜底要解决的场景
        assertNotEquals(original, ConnectionManager.fingerprintOf(
                source("MySQL", "127.0.0.1", 3306, "shop", "root", "ENC-V2")), "密码变了");
    }

    @Test
    @DisplayName("指纹里不包含 id —— 池子本来就是按 id 分的，指纹只描述「配置」")
    void fingerprintShouldNotDependOnId() {
        DbSource a = base();
        DbSource b = base();
        b.setId(999L);
        assertEquals(ConnectionManager.fingerprintOf(a), ConnectionManager.fingerprintOf(b));
    }
}