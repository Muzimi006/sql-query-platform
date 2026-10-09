# AI 协作与工程约束

本仓库在开发过程中使用 AI 编码工具。这里记录**工具分工**和**仓库侧强制执行的约束** ——
目的是让任何接手的人（无论用不用 AI）都知道边界在哪、以及边界是被什么守住的。

---

## 一、工具与分工

工具（agent 编排层）：

| 工具 | 用途 |
|---|---|
| **Claude Code** | 跨文件的批量改造、在陌生代码库里做定位、编写回归测试；项目级命令白名单通过 `settings.json` 固定 |
| **DeepSeek Harness** | 工作流编排与上下文组织；把重复性任务沉淀成可复用的流程；长文本处理（日志与报错信息分析、文档批量生成与校对） |

模型（接入上面两个工具中使用）：

| 模型接口 | 说明 |
|---|---|
| **DeepSeek API** | 不是一个独立的工具，而是接入 Claude Code / DeepSeek Harness 中使用的模型接口 —— 同一套工作流可以替换底层模型，工具层与模型层是分开的两层 |

分工原则：**要"想"的交给模型，要"跑"的交给脚本，要"信"的交给测试。**

---

## 二、仓库侧强制执行的约束

### 提交与合并

- `mvn -B test` 必须全绿，CI（`.github/workflows/ci.yml`）跑同一套
- 测试分两层：**单元测试不依赖外部中间件**；需要 MySQL / Redis / RabbitMQ 的用例打
  `@Tag("integration")`，由 surefire 默认排除 —— 这样 CI 不需要起中间件，反馈速度快

### 代码约定

| 约定 | 原因 |
|---|---|
| 外部依赖调用（网络 / Redis / 数据库 / 消息）**必须显式配置超时** | 超时不是可选项；"挂起"比"拒绝连接"更危险 |
| 失败路径必须**显式决定**是降级、重试还是快速失败，并且**留日志** | 静默降级等于故障不可观测 |
| 捕获异常的范围要精确（例如只捕获 `DuplicateKeyException` 而不是它的父类） | 过宽的 catch 会把不相干的错误翻译成错误的提示 |
| 同一类资源的申请收敛到**唯一入口** | 散落多处时，保护措施一定会不一致 |
| 魔法数字抽成命名常量 | 同一个值出现在多处，迟早不一致 |
| 序列化 / 反序列化边界**必须有测试** | 这类失败是静默的 |
| 参数校验放在方法**入口** | 快速失败，且让这段逻辑可以脱离中间件做单元测试 |

---

## 三、回归测试覆盖的失败模式

下面每一条都对应仓库里真实存在的测试：

| 失败模式 | 守住它的东西 |
|---|---|
| 改完没编译就提交 | GitHub Actions：`mvn -B test` |
| 手工装配 `SqlSessionFactory` 时漏掉分页插件 → 分页静默失效（全表查询、`total` 恒为 0） | `MybatisPlusWiringIntegrationTest`（真实 MySQL：断言 SQL 真的拼了 LIMIT） |
| 漏掉 `MetaObjectHandler` → `created_at` 被显式写成 NULL，连库表默认值都被顶掉 | `MybatisPlusWiringIntegrationTest`（**直接读数据库列**，而不是读实体） |
| jsqlparser 版本低于 MyBatis-Plus 声明的版本 → 分页插件实例化时 `InstantiationError` | `pom.xml` 里锁版本 + 注释说明原因（这是**只有真正用到插件时才暴露**的失败，测试拦不住） |
| 客户端错误掉进兜底分支，被报成「系统异常」 | `GlobalExceptionHandlerTest`（每条断言都要求答案**不是**「系统异常」） |
| 未知角色兜底成 SELECT、`null` 角色抛 NPE | `SqlPermissionServiceTest`（fail-closed） |
| `USER` 与 `ADMIN` 配成同一套权限（"按角色区分"是个摆设） | `SqlPermissionServiceTest`（断言两个角色的权限集合**不相等**） |
| 权限层放行了写语句，但执行层用 `executeQuery` 做不到 | `QueryWriteStatementIntegrationTest`（真实 MySQL：验"数据真的被改了"，不只是接口不报错） |
| 导出被重复投递时重跑并覆盖文件 | `ExportConsumerIdempotencyTest` + `ExportIdempotencyIntegrationTest`（真实 Broker 重投，断言文件 mtime 不变） |
| 导出任务卡在 PENDING / RUNNING 没人管 | `ExportTaskRecoveryJobTest` |
| 拿 MySQL 的危险函数名单去检查 PostgreSQL 的 SQL（"检查了但什么都没检查"） | `SqlDialectTest`（断言两个方言的名单**无交集**）+ `PostgresDialectIntegrationTest`（真实 PG） |
| PG 的 `pg_catalog` 默认在 search_path 里，裸表名绕过前缀检查 | `PostgresDialectIntegrationTest`（`select * from pg_shadow`） |
| `connectTimeout` 单位不同（MySQL 毫秒 / PG 秒）导致"设了等于没设" | `JdbcUrlUtilTest` |
| 数据源类型填成不支持的数据库，却被静默当成 MySQL 处理 | `SqlDialectTest` + `JdbcUrlUtilTest`（必须报错并列出支持的方言） |
| 换密钥后老数据解不开 | `AesUtilTest`（轮换后老密文仍能解密、重新加密幂等） |
| 黑名单 TTL 与 token 生命周期脱钩（写死天数） | `JwtUtilRemainingValidityTest`（造一个"用了 6 天"的 token 才测得出来） |
| 用签名的**最后一个字符**造篡改用例 —— 那一位的低位在 Base64URL 解码时被忽略，用例约 1/16 概率失败 | `JwtInterceptorVerifyCountTest`（改第一个字符 + 一条用例专门锁定这个前提） |
| 缓存序列化静默失败（时间类型不支持、异常被吞） | `JacksonConfigTest` |
| 分页参数无上限，一次拉全表 | `PaginationValidationTest` |
| 同一个 token 在一次请求里被重复验签 | `JwtInterceptorVerifyCountTest` |
| 篡改 payload、或换密钥重签的 token 被放行 | `JwtInterceptorVerifyCountTest` |
| CSV 导出编码随平台变化（Windows GBK / Linux UTF-8） | `ExportCsvEncodingTest` |
| 「停用」的数据源仍可被查询或导出 | `DbSourceEntryPointTest` |
| 停用之后无法改回启用 | `DbSourceEntryPointTest` |
| 并发插入重名数据源绕过应用层查重 | `DbSourceNameUniqueTest` |
| JDBC 连接串缺少建连超时 | `JdbcUrlUtilTest` |
| 状态字段被写入非法值 | `DbSourceStatusValidationTest` |
| `SqlValidateUtil` 里存在第二个校验入口，绕过危险 SQL 与单语句检查 | 删除该入口；`SqlValidateUtilTest` 对准生产唯一入口 `validate` |
| 白名单缺失（传 `null`）时被当成"放行" | `SqlValidateUtilTest`：空白名单必须全拒 |

---

## 四、边界

- 本文件描述的是**本仓库当前的协作方式与约定**，不评价任何工具的能力
- 这些约定是逐步加上去的：每一条背后都对应一次真实故障或一次代码审查

### 用什么衡量"AI 参与开发"这件事做得好不好

不是"用了多少 AI"，而是**人有没有在做判断**。几个本仓库里真实发生过的例子：

| 场景 | 人的判断体现在哪 |
|---|---|
| AI 给出的修法按文档照做会让应用起不来 | 先跑一遍、发现 `InstantiationError`，再去反编译 jar 查出版本不兼容 —— **没有把"代码写对了"等同于"能跑"** |
| AI 说"改成 AST 就安全了" | 又去实测 MySQL 的**可执行注释** `/*! ... */`，发现解析器把它整个丢掉 —— **没有停在第一个看起来正确的答案上** |
| 一个测试通过了 | 回过头问"**如果我改错了，这个验证会不会失败**" —— 黑名单 TTL 那次就是靠这个问题发现原来的验证什么都没证明 |
| 一个偶发失败的用例 | 不是重跑一遍让它变绿，而是去查 `Base64URL` 末位字符的填充位 —— 并且发现自己新写的用例踩了同一个坑 |
| AI 提出的结论与自己看到的不一致 | 用 32 线程并发测试去证伪/证实，而不是挑一个听起来更好的说法写进文档 |

一句话：**AI 负责把可能性铺开，人负责决定哪个是真的、以及怎么证明它是真的。**
