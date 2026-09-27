package com.example.sqlquery.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Component
public class RateLimitUtil {

    private final StringRedisTemplate redisTemplate;

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

    public RateLimitUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 固定窗口限流：单位时间内的请求数上限。 */
    public boolean tryAcquire(String key, int limit, int seconds) {
        Long result = redisTemplate.execute(
                FIXED_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(limit),
                String.valueOf(Duration.ofSeconds(seconds).toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }

    /** 滑动窗口限流：任意长度为 seconds 的时间窗内请求数不超过 limit。 */
    public boolean tryAcquireSlidingWindow(String key, int limit, int seconds) {
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(Duration.ofSeconds(seconds).toMillis()),
                String.valueOf(limit),
                now + "-" + UUID.randomUUID()
        );
        return Long.valueOf(1L).equals(result);
    }
}
