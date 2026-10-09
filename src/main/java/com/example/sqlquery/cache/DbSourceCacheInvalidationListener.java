package com.example.sqlquery.cache;

import com.example.sqlquery.util.ConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订阅数据源失效广播，在本实例上执行失效。
 *
 * <p>它只做一件事：<b>把别的实例发来的变更落到本进程的连接池上</b>。
 * 背景与"为什么是连接池而不是缓存"见 {@link DbSourceCacheInvalidator} 的类注释。
 *
 * <p>注意发布者自己也会收到这条消息（Redis 的发布订阅不排除发布者）——
 * 所以失效动作必须是<b>幂等</b>的：重复驱逐一个不存在的连接池，
 * 重复删除一个不存在的键，都必须是无害的。当前的实现满足这一点。
 */
@Slf4j
@Component
public class DbSourceCacheInvalidationListener implements MessageListener {

    private final DbSourceCacheInvalidator invalidator;
    private final ConnectionManager connectionManager;

    public DbSourceCacheInvalidationListener(DbSourceCacheInvalidator invalidator,
                                             ConnectionManager connectionManager) {
        this.invalidator = invalidator;
        this.connectionManager = connectionManager;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        Long dataSourceId;
        try {
            dataSourceId = Long.valueOf(body);
        } catch (NumberFormatException e) {
            // 频道上出现了不是我们格式的消息（人工用 redis-cli 发了一条、或者别的系统占了同名频道）。
            // 直接丢弃并告警，不能让一条脏消息把监听线程打挂 —— 那会导致后续所有广播都收不到。
            log.error("收到无法解析的数据源失效广播，已丢弃。body={}", body);
            return;
        }

        try {
            // 清掉本实例的连接池：这是广播存在的真正理由
            connectionManager.evict(dataSourceId);
            // 顺带删缓存键。别的实例已经删过一次了，但这一步是幂等的；
            // 保留了它是因为"收到失效通知就把相关本地状态清干净"比"依赖发送方是否记得清"更稳
            invalidator.deleteCacheQuietly(DbSourceCacheInvalidator.cacheKey(dataSourceId));

            log.info("收到数据源变更广播，本实例已失效连接池与缓存。dataSourceId={}", dataSourceId);
        } catch (Exception e) {
            // 单条消息处理失败不能影响后续消息
            log.error("处理数据源失效广播失败。dataSourceId={}", dataSourceId, e);
        }
    }
}
