package com.example.sqlquery.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;

import java.time.Duration;

@Component
public class RateLimitUtil {

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1]\n"
                    + "local now = tonumber(ARGV[1])\n"
                    + "local window = tonumber(ARGV[2])\n"
                    + "local limit = tonumber(ARGV[3])\n"
                    + "local windowStart = now - window\n"
                    + "redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)\n"
                    + "local count = redis.call('ZCARD', key)\n"
                    + "if count >= limit then return 0 end\n"
                    + "redis.call('ZADD', key, now, tostring(now))\n"
                    + "redis.call('PEXPIRE', key, window)\n"
                    + "return 1",
            Long.class
    );


    public RateLimitUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, int limit, int seconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(seconds));
        }
        return count != null && count <= limit;
    }

    public boolean tryAcquireSlidingWindow(String key, int limit, int seconds) {
        Long result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(Duration.ofSeconds(seconds).toMillis()),
                String.valueOf(limit)
        );
        return Long.valueOf(1L).equals(result);
    }
}
