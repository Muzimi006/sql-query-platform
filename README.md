# SQL 查询与审核平台

一个基于 Spring Boot 的在线 SQL 查询平台，支持多数据源管理、SQL 安全校验、查询历史、收藏和异步导出。

## 功能

- 用户注册 / 登录（JWT 认证）
- 密码加密（BCrypt）
- 多数据源管理（支持 MySQL，兼容 openGauss）
- 数据源连接测试（JDBC）
- SQL 查询工作台（仅允许 SELECT）
- 查询历史记录
- 收藏常用 SQL
- 异步导出 CSV（RabbitMQ）
- 数据源密码 AES 加密存储

## 技术栈

- Spring Boot 4.1.0
- Java 17
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ
- JWT
- JSqlParser

## 项目结构

```text
src/main/java/com/example/sqlquery
├── common       // 统一返回
├── config       // 配置类、拦截器、消费者
├── controller   // 接口层
├── dto          // 请求参数
├── entity       // 数据库实体
├── exception    // 异常处理
├── mapper       // 数据访问层
├── service      // 业务层
├── util         // 工具类
└── vo           // 返回对象
```

## 本地运行

### 环境要求

- JDK 17
- Maven 3.9+
- MySQL 8+
- RabbitMQ 3.x

### 配置环境变量

```text
DB_USERNAME=root
DB_PASSWORD=你的数据库密码
JWT_SECRET=你的JWT密钥
AES_KEY=你的AES密钥（16/24/32字符）
```

### 创建数据库

执行 `sql/schema.sql` 中的建库建表语句。

### 启动

```bash
mvn spring-boot:run
```

默认地址：`http://localhost:8080`

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/datasource` | 添加数据源 |
| GET | `/api/datasource` | 数据源列表 |
| POST | `/api/datasource/test` | 测试连接 |
| POST | `/api/query/execute` | 执行 SQL |
| GET | `/api/history` | 查询历史 |
| POST | `/api/favorite` | 收藏 SQL |
| POST | `/api/export` | 创建导出任务 |
| GET | `/api/export` | 导出任务列表 |

> 除注册和登录外，所有接口需要在请求头携带：
> `Authorization: Bearer <token>`

## 说明

- 当前项目为学习用途，部分功能（Redis、RabbitMQ 高可用）尚未接入
- 数据库密码、JWT 密钥、AES 密钥均通过环境变量注入，不写入配置文件
