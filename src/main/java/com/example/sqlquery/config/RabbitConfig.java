package com.example.sqlquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Queue;

@Configuration
public class RabbitConfig {

    public static final String EXPORT_QUEUE = "export.queue";

    @Bean
    public Queue exportQueue() {
        return new Queue(EXPORT_QUEUE, true);
    }
}
