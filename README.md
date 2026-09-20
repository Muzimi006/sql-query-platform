# SQL 查询与审核平台

一个基于 Spring Boot 的在线 SQL 查询平台，支持用户认证、多数据源管理、SQL 安全校验、查询历史、收藏、异步导出、限流和审计日志。

GitHub：https://github.com/Muzimi006/sql-query-platform

## 功能

- 用户注册 / 登录 / 登出（JWT + Redis 黑名单）
- 密码加密（BCrypt）
- 数据源管理：添加、修改、删除、分页、启用/禁用
- 数据源连接测试（JDBC）
- SQL 查询工作台：JSqlParser 白名单校验、只允许单条语句、拦截危险结构
- 查询历史：分页、按状态/数据源筛选
- 收藏常用 SQL
- 异步导出 CSV（RabbitMQ）
- Redis：数据源元数据缓存、滑动窗口限流（Lua 原子脚本）
- AOP 审计日志
- Docker Compose 一键部署

## 技术栈

- Spring Boot 4.1.0
- Java 17
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ
- JWT
- JSqlParser
- HikariCP
- AOP
- Docker Compose

## 项目结构

```text
src/main/java/com/example/sqlquery
├── common       // 统一返回、ThreadLocal 用户上下文
├── config       // 配置类、拦截器、AOP、SQL 权限、消费者
├── controller   // 接口层
├── dto          // 请求参数
├── entity       // 数据库实体
├── exception    // 异常处理
├── mapper       // 数据访问层
├── service      // 业务层
├── util         // JWT、AES、JDBC、连接池、限流等工具
└── vo           // 返回对象
```

## 本地运行

### 环境要求

- JDK 17
- Maven 3.9+
- MySQL 8+
- Redis 7+
- RabbitMQ 3.x（可选，使用导出功能时需要）

### 配置环境变量

```text
DB_USERNAME=root
DB_PASSWORD=你的数据库密码
JWT_SECRET=你的JWT密钥
AES_KEY=你的AES密钥（16/24/32字符）
RABBITMQ_HOST=localhost
REDIS_HOST=localhost
```

### 初始化数据库

执行 `sql/schema.sql` 中的建库建表语句。

### 启动

```bash
./mvnw.cmd spring-boot:run
```

默认地址：`http://localhost:8080`

### Docker 启动

```bash
docker compose up -d
```

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/logout` | 登出 |
| POST | `/api/datasource` | 添加数据源 |
| GET | `/api/datasource` | 数据源列表 |
| GET | `/api/datasource/page` | 数据源分页 |
| POST | `/api/datasource/test` | 测试连接 |
| PUT | `/api/datasource/{id}/status` | 启用/禁用数据源 |
| POST | `/api/query/execute` | 执行 SQL |
| GET | `/api/history` | 查询历史 |
| GET | `/api/history/page` | 历史分页/筛选 |
| POST | `/api/favorite` | 收藏 SQL |
| POST | `/api/export` | 创建导出任务 |
| GET | `/api/export` | 导出任务列表 |
| GET | `/api/export/{id}/download` | 下载导出文件 |

> 除注册、登录、登出外，所有接口需要在请求头携带：
> `Authorization: Bearer <token>`

## 说明

- 当前项目为学习用途，用于演示 Java 后端核心能力
- 数据库密码、JWT 密钥、AES 密钥均通过环境变量注入，不写入配置文件
- 查询平台建议使用只读账号连接业务库，配合应用层 SQL 白名单做多层防御
