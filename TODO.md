# 项目待办清单

## 后期再处理

- [x] `createdAt` / `updatedAt` 改为 MyBatis-Plus 自动填充
  - `createdAt` 加 `@TableField(fill = FieldFill.INSERT)`
  - `updatedAt` 加 `@TableField(fill = FieldFill.INSERT_UPDATE)`
  - 新建 `MyMetaObjectHandler` 统一填充当前时间
  - 目前先依赖 MySQL 默认值

- [x] 返回结果不暴露 `password`
  - 已创建 `UserVO`，只返回 `id / username / nickname / role / status / createdAt / updatedAt`
  - 注册/登录接口已改用 VO 返回

- [x] 并发注册同一个用户名的问题
  - 依赖数据库 `username` 唯一索引兜底
  - 捕获唯一键冲突，返回友好提示“用户名已存在”
  - 或使用 Redis 分布式锁 / 数据库锁

- [x] 处理 `jackson-databind` 安全漏洞提示
  - 当前传递依赖 `jackson-databind 2.21.4` 有 IDEA 安全告警
  - 学习阶段可忽略，不影响运行
  - 以后正式项目可升级到修复版本，或显式指定新版 `jackson-databind`

- [x] 处理 `amqp-client` 安全漏洞提示
  - 当前传递依赖 `amqp-client 5.30.0` 有 IDEA 安全告警
  - 学习阶段可忽略，不影响运行
  - 以后正式项目可升级到修复版本

- [x] Redis 相关功能（已接入接口限流，缓存/分布式锁待做）
  - 当前项目未接入 Redis
  - 以后接入：数据源元数据缓存、接口限流、分布式锁
  - 需要本机安装 Redis 或使用 Docker

- [x] RabbitMQ 异步导出
  - 已安装 RabbitMQ Docker 容器
  - 已接入 `RabbitConfig`、`ExportConsumer`、`RabbitTemplate`

- [x] JWT 密钥配置化
  - 已通过 `@Value("${jwt.secret}")` 读取配置

- [x] JWT 主动注销 + Redis 黑名单
  - 当前 JWT 无法主动失效
  - 创建 `POST /api/auth/logout` 接口
  - 退出时把 token 存入 Redis 黑名单，过期时间 = JWT 剩余有效期
  - `JwtInterceptor` 校验前先查黑名单
  - 配合 HTTPS 保证传输安全
  - `application.yml` 使用环境变量 `${JWT_SECRET}`

- [x] 大结果集处理
  - 当前执行 SQL 时结果全部读入内存
  - 限制最大返回行数，如超过 1000 行只返回前 1000 行并提示
  - 或改为流式处理

- [x] CSV 导出特殊字符转义
  - 当前单元格内容直接写入 CSV
  - 内容含逗号、引号、换行会导致 CSV 错乱
  - 需要做 CSV 转义：双引号包裹、内部引号翻倍

- [x] 全局异常处理器记录日志
  - 当前兜底异常只返回“系统异常”，未打印堆栈
  - 使用 `@Slf4j` 记录 `log.error("系统异常", e)`

- [x] ThreadLocal 存当前用户
  - 当前每个 Controller 重复解析 Token 获取 userId
  - 拦截器解析 userId 放入 ThreadLocal
  - Controller 用 `UserContext.get()` 获取
  - 请求结束 `ThreadLocal.remove()` 防内存泄漏

- [x] 查询历史分页 + 筛选
  - 支持 `page` / `size`
  - 支持按状态筛选：SUCCESS / FAILED
  - 支持按数据源筛选

- [x] 数据源元数据缓存
  - 数据源信息缓存到 Redis
  - 查询先走 Redis，没有再查 MySQL
  - 修改/删除数据源时清理缓存

- [x] 限流升级为滑动窗口
  - 当前固定窗口存在临界突发问题
  - 使用 Redis ZSet 实现滑动窗口

- [x] 操作审计日志
  - 使用 AOP 记录写操作
  - 记录用户、接口、参数、结果、时间

- [x] SQL 执行超时
  - 给 `Statement` 设置 `queryTimeout`
  - 防止慢 SQL 拖死请求

- [x] 动态数据源连接池
  - 当前每次执行 SQL 都新建 JDBC 连接
  - 为每个数据源缓存连接池，复用连接

- [x] Docker 部署
  - 编写 Dockerfile
  - 编写 docker-compose.yml 一键启动应用 + MySQL + Redis + RabbitMQ

- [x] 单元测试 / 接口测试
  - 至少为 `SqlValidateUtil`、`AesUtil`、核心 Service 写测试

- [x] 数据源密码加密存储
  - 已通过 `AesUtil` 加密存储
  - 添加/修改数据源时加密，执行 SQL/导出时解密

- [x] DTO 参数校验
  - 已创建 DTO 并加 `@NotBlank` / `@NotNull`
  - Controller 已加 `@Valid`
  - `GlobalExceptionHandler` 已处理 `MethodArgumentNotValidException`

## DbSource 后续方法增加计划

- [x] 增加 `listByUserId(Long userId)` 的查询历史列表方法
  - 已实现，按 `userId` 查询，按 `created_at` 倒序

- [x] 增加 `getByIdAndUserId(Long userId, Long id)`
  - 根据 id 查询单个数据源，并校验属于当前用户
  - Controller 中多个接口会复用

- [x] 真正实现 `testConnection`
  - 已用 JDBC 实现

- [x] 数据源密码加密存储
  - 已通过 `AesUtil` 实现

- [x] 增加 `checkNameUnique(Long userId, String name)`
  - 同一个用户下数据源名称不能重复

- [x] 增加分页查询
  - 数据源多时，支持分页返回

- [x] 增加禁用/启用数据源
  - 通过 `status` 字段管理数据源状态

- [x] `testConnection` 返回具体错误信息
  - 当前连接失败只返回 `false`
  - 以后改为抛 `BusinessException` 或返回错误详情，方便前端提示用户
  - 例如：`无法连接到数据库：Access denied for user ...`

- [x] 导出模块下载接口改为真正文件下载
  - 当前 `/api/export/{id}/download` 只返回文件路径
  - 以后改为读取 CSV 文件并写入 HTTP 响应流
  - 设置 `Content-Disposition` 响应头，支持浏览器下载
  - 处理文件名中文乱码和文件不存在情况


## SQL 安全校验增强

- [x] 防止多语句绕过
  - 当前 `CCJSqlParserUtil.parse(sql)` 只解析第一条语句
  - 改为 `parseStatements(sql)`，只允许单条语句
  - 只允许单条 SELECT

- [x] 拦截伪装成 SELECT 的危险结构
  - 检查 `SELECT ... INTO OUTFILE`
  - 检查 `SELECT ... INTO DUMPFILE`
  - 检查 `LOAD_FILE`
  - 检查 `SLEEP` / `BENCHMARK` 等危险函数
  - 检查是否访问 `information_schema` / `mysql` 等系统库

- [ ] 数据库层权限兜底
  - 查询账号只授予 SELECT
  - 不授予 FILE、PROCESS 等高危权限
  - 限制只能访问业务库

## 缓存三大问题

- [x] 缓存穿透
  - 当前查询不存在的数据源时，不会缓存空值，每次都会打到 MySQL
  - 方案一：缓存空值，设置较短过期时间（如 5 分钟）
  - 方案二：布隆过滤器

- [x] 缓存击穿
  - 热点数据缓存刚好过期时，大量请求同时打到 MySQL
  - 方案一：分布式锁，只允许一个线程去查库
  - 方案二：逻辑过期，异步更新缓存

- [x] 缓存雪崩
  - 当前 TTL 固定 30 分钟，大量 key 可能同时过期
  - Redis 异常时没有降级逻辑
  - 方案一：TTL 加随机值
  - 方案二：Redis 异常时降级查数据库
  - 方案三：Redis 高可用（主从 + 哨兵 / Cluster）