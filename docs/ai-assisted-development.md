# AI 协作与工程约束

本仓库在开发过程中使用 AI 编码工具。这里记录**工具分工**和**仓库侧强制执行的约束** ——
目的是让任何接手的人（无论用不用 AI）都知道边界在哪、以及边界是被什么守住的。

---

## 一、工具与分工

| 工具 | 用途 |
|---|---|
| **Claude Code** | 跨文件的批量改造、在陌生代码库里做定位、编写回归测试；项目级命令白名单通过 `settings.json` 固定 |
| **DeepSeek API** | 长文本处理：日志与报错信息分析、文档批量生成与校对 |
| **DeepSeek Harness** | 工作流编排与上下文组织；把重复性任务沉淀成可复用的流程 |

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

---

## 四、边界

- 本文件描述的是**本仓库当前的协作方式与约定**，不评价任何工具的能力
- 这些约定是逐步加上去的：每一条背后都对应一次真实故障或一次代码审查
