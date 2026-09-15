package com.example.sqlquery.service.impl;

import com.example.sqlquery.service.TokenBlacklistService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final String BLACK_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void add(String token, Duration expire) {
        redisTemplate.opsForValue().set(BLACK_PREFIX+token,"1",expire);
    }

    @Override
    public boolean contains(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACK_PREFIX+token));
    }
}
