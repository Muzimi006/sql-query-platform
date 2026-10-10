package com.example.sqlquery.util;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 基于 Redis 的限流器。
 *
 * <p>限流是<b>保护性</b>措施，不是<b>功能性</b>措施。所以 Redis 不可用时的失败方向
 * 统一选择"放行 + ERROR 日志"，而不是"拒绝"：
 * 限流失效只是失去了超额保护，而拒绝会让一次缓存故障直接升级成整站不可用
 * （已经实测复现过：Redis 停掉后，除注册登录外所有接口返回「系统异常」）。
 *
 * <p>这个取舍是有代价的，必须说清楚：<b>Redis 不可用期间限流形同虚设</b>。
 * 真要在大流量下守住，靠的是网关层的限流，而不是业务进程里的这一段。
 */
@Slf4j
@Component
public class RateLimitUtil {

    private final StringRedisTemplate redisTemplate;
    private final SqlPlatformMetrics metrics;

    /**
     * 固定窗口限流。
     *
     * <p>INCR 与 EXPIRE 必须在服务端原子执行：分两步发命令时，若进程在两条命令之间退出，
     * key 会永久存在（永不过期），该用户将被永久限流。
     *
     * <p>ARGV[1] = limit，ARGV[2] = 窗口毫秒数；返回 1 放行，0 超限。
     */
    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1])\n"
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end\n"
                    + "if count > tonumber(ARGV[1]) then return 0 end\n"
                    + "return 1",
            Long.class
    );

    /**
     * 滑动窗口限流。
     *
     * <p>ZSet 的 member 必须唯一：早期实现用 \`tostring(now)\` 当 member，
     * 同一毫秒内的并发请求会互相覆盖，导致 ZCARD 少计数、限流被绕过。
     * 现在由调用方传入「时间戳 + UUID」作为 member。
     *
     * <p>ARGV[1] = 当前毫秒时间戳，ARGV[2] = 窗口毫秒数，ARGV[3] = limit，ARGV[4] = 唯一 member。
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1]\n"
                    + "local now = tonumber(ARGV[1])\n"
                    + "local window = tonumber(ARGV[2])\n"
                    + "local limit = tonumber(ARGV[3])\n"
                    + "local member = ARGV[4]\n"
                    + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
                    + "local count = redis.call('ZCARD', key)\n"
                    + "if count >= limit then return 0 end\n"
                    + "redis.call('ZADD', key, now, member)\n"
                    + "redis.call('PEXPIRE', key, window)\n"
                    + "return 1",
            Long.class
    );

    public RateLimitUtil(StringRedisTemplate redisTemplate, SqlPlatformMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    /** 固定窗口限流：单位时间内的请求数上限。Redis 不可用时放行。 */
    public boolean tryAcquire(String key, int limit, int seconds) {
        return acquireQuietly(() -> redisTemplate.execute(
                FIXED_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(limit),
                String.valueOf(Duration.ofSeconds(seconds).toMillis())
        ), key);
    }

    /** 滑动窗口限流：任意长度为 seconds 的时间窗内请求数不超过 limit。Redis 不可用时放行。 */
    public boolean tryAcquireSlidingWindow(String key, int limit, int seconds) {
        long now = System.currentTimeMillis();
        return acquireQuietly(() -> redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(Duration.ofSeconds(seconds).toMillis()),
                String.valueOf(limit),
                now + "-" + UUID.randomUUID()
        ), key);
    }

    /**
     * 所有限流调用的<b>唯一出口</b>：把"Redis 异常怎么处理"这件事收敛到一处。
     *
     * <p>收敛的意义：散落各处时，保护措施一定会不一致 —— 有的地方吞了异常放行、
     * 有的地方直接把异常抛给用户，行为随调用点变化。
     */
    private boolean acquireQuietly(java.util.function.Supplier<Long> redisCall, String key) {
        boolean allowed;
        try {
            allowed = Long.valueOf(1L).equals(redisCall.get());
        } catch (Exception e) {
            // ⚠️ 这里刻意用 WARN 而不是 ERROR，而且刻意**不打异常堆栈**。
            //
            // 理由：这是**可预期的降级**，不是意外故障 —— Redis 只要抖一下，
            // 每个请求都会走到这里。用 ERROR + 全栈的后果是实测出来的：
            // Redis 停掉后打 20 个请求，日志涨了 505 行，其中 458 行是异常栈帧（91%）。
            // 按 ERROR 级别接告警的话，Redis 一抖就是告警风暴；
            // 日志本身也会被刷爆（真实流量下几分钟就能把磁盘写满）。
            //
            // 要看到"限流正在降级"，看这个 WARN 和 SqlPlatformMetrics 的计数器就够了。
            // 计数 + 限流日志，统一走 metrics 的降级出口（见 SqlPlatformMetrics.recordRedisDegradation）
            metrics.recordRedisDegradation("rate-limit", "key=" + key, e);
            return true;
        }

        if (!allowed) {
            // 被限流是"系统按预期工作"，不是错误，所以只记指标、不打日志 ——
            // 打日志的话，一次正常的限流会在日志里刷出大量 ERROR/WARN，
            // 把真正的故障淹掉。要看触发频率就查这个计数器。
            metrics.recordRateLimited();
        }
        return allowed;
    }
}
