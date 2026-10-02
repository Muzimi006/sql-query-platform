# SQL 查询与审核平台

[![CI](https://github.com/Muzimi006/sql-query-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Muzimi006/sql-query-platform/actions/workflows/ci.yml)

一个基于 Spring Boot 的在线 SQL 查询与审核平台，提供用户认证、多数据源管理、SQL 安全校验、查询历史与收藏、异步导出、接口限流与操作审计能力。

仓库地址：https://github.com/Muzimi006/sql-query-platform

---

## 核心设计

本章说明各模块的关键设计决策与取舍，功能清单见后文。

### 1. SQL 执行的四道防线

平台需要连接用户自行配置的业务数据库，而用户提交的 SQL 属于不可信输入，因此在执行前设置分层拦截：

| 层 | 手段 | 拦截对象 |
|---|---|---|
| 解析层 | JSqlParser `parseStatements` + 语句类型白名单（**默认拒绝**） | 多语句注入（`select 1; drop table x`）、非 SELECT 语句 |
| 关键字层 | 危险结构匹配 | `INTO OUTFILE` / `INTO DUMPFILE` / `LOAD_FILE` / `SLEEP` / `BENCHMARK`、系统库访问 |
| 资源层 | `setMaxRows(1000 + 1)` + `setQueryTimeout(30s)` | 大结果集占用过多内存、慢查询长期占用连接 |
| 数据库层 | 建议配合只读账号（见「已知限制」） | 应用层被绕过后的兜底 |

> 查询链路与导出链路共用同一套校验入口（`SqlValidateUtil` + `SqlPermissionConfig`）。
> 早期版本中导出链路缺少这一步校验。同一个数据源、同一个连接池，两条入口的安全级别应当保持一致，该问题已修复。

### 2. 动态多数据源连接池

数据源由用户在运行期自行添加、数量不固定，因此无法采用 Spring 的静态多数据源配置，改为按数据源记录动态创建与销毁连接池：

- 每个数据源对应一个独立的 HikariCP 连接池，缓存在 `ConcurrentHashMap` 中
- 数据源**修改 / 删除 / 禁用**时同步 `evict` 并关闭旧连接池。若不做 evict，连接信息变更后仍会继续复用旧连接池，且已被移除的数据源所对应的连接池无法释放
- 连接信息从 Redis 缓存读取，缓存未命中时回源数据库

### 3. 缓存一致性与失效场景

数据源元数据缓存 30 分钟，并针对三种典型失效场景分别处理：

- **穿透**：查询不存在的记录时缓存空值（`"NULL"`，5 分钟 TTL），避免请求持续落到数据库
- **击穿**：热点 key 过期时使用 **Redis 分布式锁**（唯一 token + Lua 脚本释放），保证只有一个线程回源
- **雪崩**：TTL 附加随机值（+0~300 秒），避免大量 key 同时过期；Redis 异常时降级为直接查询数据库

数据源变更时，**缓存与连接池同时失效**。

### 4. 限流的原子性

两种算法均在 **Redis 内通过 Lua 脚本原子执行**：

- **固定窗口**：`INCR` 与首次 `PEXPIRE` 必须原子。分两步下发命令时，若进程在中间退出，该 key 将永不过期，对应用户会被**永久限流**
- **滑动窗口**：以 ZSet 记录每次请求的时间戳，任意长度窗口内计数精确；member 使用「时间戳 + UUID」保证唯一。若以纯时间戳作为 member，**同一毫秒内的并发请求会互相覆盖**，导致计数偏少、限流被绕过

### 5. 密码与凭据

- **用户密码**：BCrypt 哈希。仅引入 `spring-security-crypto`，不引入完整的 Spring Security 自动配置，避免为一个哈希函数引入整套过滤器链
- **数据源密码**：**AES/GCM/NoPadding**，每次生成随机 12 字节 IV，密文格式为 `v1:` + Base64(IV ‖ 密文 + 认证标签)
  - 相较 ECB：GCM 为认证加密，自带完整性校验；随机 IV 保证**相同明文不会产生相同密文**
  - 保留只读的 ECB 解密分支，用于存量数据迁移
  - 密钥长度在启动阶段校验（16 / 24 / 32 字节），配置错误时立即失败，而非在运行期抛出难以定位的异常

### 6. 异步导出

导出任务通过 RabbitMQ 异步执行：接口仅写入一条 `PENDING` 记录即返回，消费者执行后推进状态机 `PENDING → RUNNING → SUCCESS / FAILED`，失败原因落库可查。CSV 输出对引号、逗号与换行做了转义处理。

---

## 请求链路

### 查询请求

```text
POST /api/query/execute
  │
  ├─ JwtInterceptor     校验 Bearer Token → 查 Redis 黑名单 → 写入 UserContext(ThreadLocal)
  │
  ├─ QueryController    滑动窗口限流（Redis + Lua，10 次 / 60 秒）
  │
  ├─ QueryServiceImpl   ├─ 校验数据源归属与启用状态
  │                     ├─ SqlValidateUtil 解析 + 语句类型白名单 + 危险结构匹配
  │                     ├─ ConnectionManager 取该数据源的 HikariCP 连接
  │                     ├─ setMaxRows(1001) / setQueryTimeout(30)
  │                     └─ 执行 → 超过 1000 行截断并置 truncated
  │
  ├─ QueryHistoryService 记录历史（成功 / 失败、耗时、错误信息）
  │
  └─ AuditLogAspect     AOP 记录写操作审计日志
```

### 导出任务

```text
POST /api/export  →  校验数据源归属 + SQL 审核（与查询链路同一套）
                  →  落库 ExportTask(PENDING)
                  →  RabbitMQ 投递 taskId
                  →  ExportConsumer 消费 → RUNNING → 执行 SQL → 写 CSV
                  →  SUCCESS / FAILED（错误信息落库）

GET /api/export/{id}/download  →  校验归属 + 任务状态 → 流式写回 CSV
```

---

## 功能清单

- 用户注册 / 登录 / 登出（JWT + Redis 黑名单）
- 密码加密（BCrypt）
- 数据源管理：添加、修改、删除、分页、启用 / 禁用
- 数据源连接测试（JDBC）
- SQL 查询工作台：JSqlParser 白名单校验、仅允许单条语句、拦截危险结构
- 查询历史：分页、按状态 / 数据源筛选
- 收藏常用 SQL
- 异步导出 CSV（RabbitMQ）
- Redis：数据源元数据缓存、滑动窗口限流（Lua 原子脚本）、分布式锁
- AOP 操作审计日志
- Docker Compose 一键部署

## 技术栈

| 类别 | 选型 |
|---|---|
| 框架 | Spring Boot 4.1.0 · Java 17 |
| 持久层 | MyBatis-Plus |
| 存储 | MySQL · Redis |
| 消息 | RabbitMQ |
| 认证与加密 | JWT（auth0 java-jwt）· BCrypt · AES/GCM |
| SQL 解析 | JSqlParser |
| 连接池 | HikariCP |
| 切面 | Spring AOP |
| 部署 | Docker Compose |
| 测试与 CI | JUnit 5 · Mockito · GitHub Actions |

## 项目结构

```text
src/main/java/com/example/sqlquery
├── common       // 统一返回、ThreadLocal 用户上下文、常量
├── config       // 配置类、拦截器、AOP、SQL 权限、MQ 消费者
├── controller   // 接口层
├── dto          // 请求参数
├── entity       // 数据库实体
├── exception    // 异常处理
├── mapper       // 数据访问层
├── service      // 业务层
├── util         // JWT、AES、JDBC URL、连接池、限流、SQL 校验
└── vo           // 返回对象
```

---

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

---

## 测试与 CI

测试分为两层，其中单元测试不依赖任何外部中间件：

| 层 | 命令 | 依赖 |
|---|---|---|
| 单元测试 | `mvn test` | 无（AES、JDBC URL、SQL 校验、分页校验、密码链路等纯逻辑） |
| 集成测试 | `docker compose up -d && mvn test -Dgroups=integration` | MySQL / Redis / RabbitMQ |

需要中间件的用例标注了 `@Tag("integration")`，由 surefire 默认排除；CI（GitHub Actions）执行的是无外部依赖的单元测试层。

当前共 **12 个测试类、47 个用例**，覆盖范围包括：AES 加解密与密钥长度校验、JDBC URL 超时参数、SQL 校验（多语句 / 危险结构 / 系统库 / 空名单）、分页参数上限、JWT 单次请求验签次数、CSV 导出编码、导出文件清理、数据源入口与状态校验、并发重名、缓存序列化，以及注册 / 登录 / 改密码链路。

---

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
| PUT | `/api/datasource/{id}/status` | 启用 / 禁用数据源 |
| POST | `/api/query/execute` | 执行 SQL |
| GET | `/api/history` | 查询历史 |
| GET | `/api/history/page` | 历史分页 / 筛选 |
| POST | `/api/favorite` | 收藏 SQL |
| POST | `/api/export` | 创建导出任务 |
| GET | `/api/export` | 导出任务列表 |
| GET | `/api/export/{id}/download` | 下载导出文件 |

> 除注册、登录、登出外，所有接口需要在请求头中携带 `Authorization: Bearer <token>`。

---

## 已知限制

1. **SQL 审核的关键字层基于字符串匹配**：`checkDangerousSql` 对原始 SQL 做 `contains` 判断，可以通过注释拆分绕过（如 `INTO/**/OUTFILE`）。更稳妥的做法是基于 AST 判断，最终兜底依赖数据库侧的只读权限。
2. **多实例部署时缓存失效不彻底**：数据源变更仅清理当前实例的缓存与连接池，其他实例不会收到通知，需要引入 Redis 发布订阅来广播失效事件。
3. **缺少监控与指标**：慢查询、连接池水位、缓存命中率、限流触发次数均不可观测。
4. **AES 密钥通过环境变量注入**：仅解决了密钥不进代码库的问题，生产环境应接入密钥管理服务并支持轮换。
5. **权限模型尚未按角色区分**：`SqlPermissionConfig` 中 `USER` 与 `ADMIN` 配置的是同一套权限（均只允许 `SELECT`），且注册时角色固定为 `USER`，没有创建管理员账号的路径。白名单校验本身是生效的（`validate` 按语句类型默认拒绝），尚未落地的是「按角色区分」这一层。

## 安全说明

- 数据库密码、JWT 密钥与 AES 密钥均通过环境变量注入，不写入配置文件
- `sql/schema.sql` 中创建的 `query_user` 仅授予 `SELECT` 权限，建议使用该账号连接业务库，与应用层 SQL 白名单形成多层防御
- `docker-compose.yml` 中的凭据仅用于本地演示，部署前必须替换
