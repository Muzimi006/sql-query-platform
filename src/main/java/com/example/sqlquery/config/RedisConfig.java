package com.example.sqlquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 基础设施。
 *
 * <p>这里<b>刻意不声明</b> {@code RedisMessageListenerContainer}：
 * 它是 {@code SmartLifecycle}，一旦成为 bean，Spring 就会在<b>容器刷新阶段</b>
 * 调用它的 {@code start()}，而那个方法会同步连一次 Redis ——
 * 实测结果是"Redis 没起来，应用也起不来"。
 *
 * <p>它的生命周期改由 {@code RedisSubscriptionStarter} 以"应用就绪后再启动 +
 * 失败可重试"的方式接管。原因与实测细节见那个类。
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
