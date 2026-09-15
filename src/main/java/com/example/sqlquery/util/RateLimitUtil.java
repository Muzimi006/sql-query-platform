package com.example.sqlquery.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RateLimitUtil {

    private final StringRedisTemplate redisTemplate;

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
        long now = System.currentTimeMillis();
        long windowStart = now - Duration.ofSeconds(seconds).toMillis();

        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        redisTemplate.opsForZSet().add(key, String.valueOf(now), now);
        redisTemplate.expire(key, Duration.ofSeconds(seconds));

        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null && count <= limit;
    }
}
