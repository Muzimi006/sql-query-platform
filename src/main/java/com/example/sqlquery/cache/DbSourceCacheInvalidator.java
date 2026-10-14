package com.example.sqlquery.cache;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.infra.SchemaIntrospector;
import com.example.sqlquery.infra.ConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据源变更后的失效处理。
 *
 * <h2>为什么需要"广播"，以及需要广播的到底是什么</h2>
 *
 * <p>先纠正一个容易搞错的点：<b>缓存不在本进程里，它就在 Redis 里</b>。
 * 所以 {@code redisTemplate.delete(key)} 这一步<b>本来就是全局生效的</b> ——
 * 任何一个实例删掉键，其它实例下一次读就是 miss，会重新回源。
 * 换句话说，"多实例缓存不一致"这个说法对缓存部分并不成立。
 *
 * <p>真正<b>只存在于单个 JVM 里、别的实例看不见</b>的是连接池：
 * {@link ConnectionManager} 内部的 {@code poolCache} 是一个普通的
 * {@code ConcurrentHashMap} 字段。实例 A 改了数据源密码之后，
 * 实例 B 手里那把 HikariCP 连接池<b>还在按旧密码连</b>，
 * 它会一直用到缓存过期、重新回源、再被别的原因驱逐为止 ——
 * 在这之前，请求会随机地落到"能用"和"不能用"两种实例上，
 * 表现为间歇性失败，是那种最难排查的故障。
 *
 * <p>所以这个类的核心职责是：<b>把"本实例要失效"这件事广播出去，
 * 让每个实例都清掉自己的连接池</b>。顺带删一次缓存键是幂等的防御动作。
 *
 * <h2>与本地失效的关系</h2>
 *
 * <p>{@link #invalidateEverywhere(Long)} 先做本地失效<b>再</b>广播，而不是只广播：
 * Redis 不可用时广播必然失败，如果只依赖广播，本次变更在本实例上都不会生效 ——
 * 那是最坏的结果（改完自己都不认）。
 */
@Slf4j
@Component
public class DbSourceCacheInvalidator {

    /**
     * 失效广播的频道。
     *
     * <p>发布端与订阅端<b>共用这一个常量</b>：写成两处字面量时，
     * 改了一处不会有任何编译错误，只会表现为"广播发了但没人收到"。
     */
    public static final String INVALIDATION_CHANNEL = "datasource:cache:invalidate";

    private static final String CACHE_PREFIX = "datasource:";

    private final StringRedisTemplate redisTemplate;
    private final ConnectionManager connectionManager;
    private final SqlPlatformMetrics metrics;

    private final SchemaIntrospector schemaIntrospector;

    public DbSourceCacheInvalidator(StringRedisTemplate redisTemplate,
                                    ConnectionManager connectionManager,
                                    SqlPlatformMetrics metrics,
                                    SchemaIntrospector schemaIntrospector) {
        this.redisTemplate = redisTemplate;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
        this.schemaIntrospector = schemaIntrospector;
    }

    /** 缓存 key 的<b>唯一</b>构造入口：读、写、删、广播接收端都必须走这里。 */
    public static String cacheKey(Long id) {
        return CACHE_PREFIX + id;
    }

    /** 只让<b>本实例</b>失效。周期任务、广播接收端使用。 */
    public void evictLocal(Long id) {
        deleteCacheQuietly(cacheKey(id));
        connectionManager.evict(id);
        // ⭐ 表结构缓存也要一起失效。
        //    它以前**从来没有人调用过**（`SchemaIntrospector.evict` 是死代码），
        //    于是改了数据源指向的库/表结构之后，本实例最多 5 分钟仍按**旧表结构**拼提示词 ——
        //    而多实例下"只有收到广播的这个实例会清"，正好对齐。
        schemaIntrospector.evict(id);
    }

    /**
     * 让<b>所有实例</b>失效：本地立即生效，然后广播给其它实例。
     *
     * <p>业务侧（修改 / 删除 / 启用停用）统一调这一个。
     */
    public void invalidateEverywhere(Long id) {
        evictLocal(id);

        try {
            redisTemplate.convertAndSend(INVALIDATION_CHANNEL, String.valueOf(id));
        } catch (Exception e) {
            // 广播失败只影响"其它实例"，本实例已经失效过了。
            // 必须留下痕迹：这是一个"本来该生效却没完全生效"的动作，
            // 静默失败的结果是其它实例带着旧连接池继续跑，且没人知道。
            metrics.recordRedisDegradation("invalidate-broadcast",
                    "其它实例可能继续使用旧连接池。dataSourceId=" + id, e);
        }
    }

    /**
     * 删除缓存键，失败只记日志。
     *
     * <p>缓存是<b>加速手段</b>而不是数据来源，删不掉不会导致数据错误，
     * 最坏情况是脏值多存活到 TTL 到期；为此把业务请求打失败是不划算的。
     */
    public void deleteCacheQuietly(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            metrics.recordRedisDegradation("cache-delete", "cacheKey=" + cacheKey, e);
        }
    }
}
