package com.example.sqlquery;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.sqlquery.mapper")
// 导出文件的清理任务是 @Scheduled 驱动的；不加这个注解它会静默地永远不执行
@EnableScheduling
public class SqlQueryPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(SqlQueryPlatformApplication.class, args);
	}

}
