package com.example.sqlquery;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.sqlquery.mapper")
public class SqlQueryPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(SqlQueryPlatformApplication.class, args);
	}

}
