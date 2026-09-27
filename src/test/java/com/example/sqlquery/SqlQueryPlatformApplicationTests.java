package com.example.sqlquery;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动完整的 Spring 上下文，需要 MySQL / Redis / RabbitMQ 同时在位，
 * 因此打上 integration 标签，不进入默认的单元测试阶段。
 */
@SpringBootTest
@Tag("integration")
class SqlQueryPlatformApplicationTests {

	@Test
	void contextLoads() {
	}

}
