# SQL 查询与审核平台

[![CI](https://github.com/Muzimi006/sql-query-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Muzimi006/sql-query-platform/actions/workflows/ci.yml)

一个基于 Spring Boot 的在线 SQL 查询平台，支持用户认证、多数据源管理、SQL 安全校验、查询历史、收藏、异步导出、限流和审计日志。

GitHub：https://github.com/Muzimi006/sql-query-platform

---

## 核心设计

功能列表谁都能列，这里只写**为什么这么设计**。

### 1. SQL 执行的「四道防线」

用户提交的 SQL 是**不可信输入**，而且这个平台本身要连各种业务库，所以做了分层拦截：

| 层 | 手段 | 拦什么 |
|---|---|---|
| 解析层 | JSqlParser `parseStatements` + 角色白名单 | 多语句注入（`select 1; drop table x`）、非 SELECT 语句 |
| 关键字层 | 危险结构匹配 | `INTO OUTFILE` / `INTO DUMPFILE` / `LOAD_FILE` / `SLEEP` / `BENCHMARK`、系统库访问 |
| 资源层 | `setMaxRows(1000 + 1)` + `setQueryTimeout(30s)` | 大结果集打爆内存、慢查询长期占用连接 |
| 数据库层 | 建议配合只读账号（见「已知限制」） | 应用层被绕过后的兜底 |

> **查询链路和导出链路共用同一套校验入口**（`SqlValidateUtil` + `SqlPermissionConfig`）。
> 早期版本导出链路漏了这一步，是一个真实的一致性缺口 —— 同一个数据源、同一个连接池，两条入口的安全级别却不一样。

### 2. 动态多数据源连接池

不是静态配置多数据源，而是**按数据源记录动态创建 / 销毁连接池**：

- 每个数据源一个独立 HikariCP 池，缓存在 `ConcurrentHashMap` 里
- 数据源**修改 / 删除 / 禁用**时同步 `evict` 并关闭旧池 —— 否则改完连接信息还在用旧池，且池会泄漏
- 连接信息（含密码）从 Redis 缓存取，缓存未命中回源数据库

### 3. 缓存一致性与三大问题

数据源元数据缓存 30 分钟，并且专门处理了三种失效场景：

- **穿透**：查不到的记录缓存空值（`"NULL"`，5 分钟 TTL），避免每次都打到数据库
- **击穿**：热点 key 过期时用 **Redis 分布式锁**（唯一 token + Lua 脚本释放）保证只有一个线程回源
- **雪崩**：TTL 加随机值（+0~300 秒），避免大量 key 同时过期；Redis 异常时降级直接查库

变更数据源时**缓存与连接池双失效**。

### 4. 限流的原子性

两种算法都是 **Redis 内 Lua 原子执行**：

- **固定窗口**：`INCR` 与首次 `PEXPIRE` 必须原子 —— 分两步发命令时若进程在中间退出，key 会永不过期，用户被**永久限流**
- **滑动窗口**：ZSet 记录每次请求的时间戳，任意长度窗口内计数精确；member 用「时间戳 + UUID」保证唯一 —— 用纯时间戳做 member 时，**同一毫秒的并发请求会互相覆盖**，导致少计数、限流被绕过

### 5. 密码与凭据

- 用户密码：BCrypt（只引 `spring-security-crypto`，不引整个 security 全家桶）
- 数据源密码：**AES/GCM/NoPadding**，每次随机 12 字节 IV，密文格式 `v1:` + Base64(IV ‖ 密文+认证标签)
  - 相比 ECB：GCM 是认证加密，自带完整性校验；随机 IV 保证**相同明文不会产生相同密文**
  - 保留只读的历史 ECB 解密分支，用于存量数据迁移
  - 密钥长度在启动阶段校验（16/24/32 字节），配错立刻失败而不是运行期报晦涩异常

### 6. 异步导出

导出任务走 RabbitMQ：接口只落一条 `PENDING` 记录就返回，消费者执行后推进状态机
`PENDING → RUNNING → SUCCESS / FAILED`，失败原因落库可查；CSV 做了引号/逗号/换行的转义。

---

## 一次查询请求的完整链路

```text
POST /api/query/execute
  │
  ├─ JwtInterceptor     校验 Bearer Token → 查 Redis 黑名单 → 写入 UserContext(ThreadLocal)
  │
  ├─ QueryController    滑动窗口限流（Redis + Lua，10 次 / 60 秒）
  │
  ├─ QueryServiceImpl   ├─ 校验数据源归属
  │                     ├─ SqlValidateUtil 解析 + 角色白名单 + 危险结构匹配
  │                     ├─ ConnectionManager 取该数据源的 HikariCP 连接
  │                     ├─ setMaxRows(1001) / setQueryTimeout(30)
  │                     └─ 执行 → 超过 1000 行截断并置 truncated
  │
  ├─ QueryHistoryService 记录历史（成功/失败、耗时、错误信息）
  │
  └─ AuditLogAspect     AOP 记录写操作审计日志
```

## 一次导出任务的完整链路

```text
POST /api/export  →  校验数据源归属 + SQL 审核（与查询链路同一套）
                  →  落库 ExportTask(PENDING)
                  →  RabbitMQ 投递 taskId
                  →  ExportConsumer 消费 → RUNNING → 执行 SQL → 写 CSV
                  →  SUCCESS / FAILED（错误信息落库）

GET /api/export/{id}/download  →  校验归属 + 任务状态 → 流式写回 CSV
```

---

## 功能

- 用户注册 / 登录 / 登出（JWT + Redis 黑名单）
- 密码加密（BCrypt）
- 数据源管理：添加、修改、删除、分页、启用 / 禁用
- 数据源连接测试（JDBC）
- SQL 查询工作台：JSqlParser 白名单校验、只允许单条语句、拦截危险结构
- 查询历史：分页、按状态 / 数据源筛选
- 收藏常用 SQL
- 异步导出 CSV（RabbitMQ）
- Redis：数据源元数据缓存、滑动窗口限流（Lua 原子脚本）、分布式锁
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
- JUnit 5 + GitHub Actions

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

测试分两层，避免「必须把所有中间件都起起来才能跑一个测试」：

| 层 | 命令 | 依赖 |
|---|---|---|
| 单元测试 | `mvn test` | 无（纯逻辑：AES、JDBC URL、SQL 校验、限流脚本） |
| 集成测试 | `docker compose up -d && mvn test -Dgroups=integration` | MySQL / Redis / RabbitMQ |

需要中间件的用例打了 `@Tag("integration")`，由 surefire 默认排除；CI（GitHub Actions）跑的是无依赖的单元测试层。

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

> 除注册、登录、登出外，所有接口需要在请求头携带：
> `Authorization: Bearer <token>`

---

## 已知限制

1. **SQL 审核的关键字层是字符串匹配**：`checkDangerousSql` 对原始 SQL 做 `contains` 判断，
   可以用注释拆分绕过（如 `INTO/**/OUTFILE`），更稳的做法是基于 AST 判断。
2. **多实例部署时缓存失效不彻底**：数据源变更只清理当前实例的缓存与连接池，
   需要 Redis 发布订阅来广播失效事件。
3. **无监控与指标**：慢查询、连接池水位、限流触发次数均不可观测。
4. **AES 密钥走环境变量**：生产应接入密钥管理服务并支持轮换。

## 安全说明

- 数据库密码、JWT 密钥、AES 密钥均通过环境变量注入，不写入配置文件
- `sql/schema.sql` 中创建的 `query_user` 只授予 `SELECT`，建议用它连接业务库，
  与应用层 SQL 白名单形成多层防御
- ⚠️ `docker-compose.yml` 中的凭据仅供本地演示，部署前必须替换