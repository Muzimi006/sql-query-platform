# 更新日志

> 格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
>
> ⚠️ 这份文件记的是**对外可见的变化**（接口、配置、行为、依赖）。
> 每一个问题**是怎么被发现、怎么修的、留下了什么边界**，在
> `D:\sql查询项目学习\SQL查询平台-问题修复档案.md` 里逐条记录（按编号 B/D/A/C/E/S/M/P/U/T/V/G/N/F/H/I/J）。
>
> 两者分工：**CHANGELOG 回答「有什么变了」，档案回答「为什么变、凭什么说修好了」。**

## [Unreleased]

### 新增
- ⭐ **前端补齐到 8 个页面**：新增 **审核工单中心**（我的工单 / 待我审批、提交、详情、批准与驳回、执行中自动轮询）、
  **自然语言生成 SQL**（未启用时说明原因与开启方式）、**查询历史**、**收藏的 SQL**；重写 查询工作台 / 数据源 / 导出 / 登录。
  **零 UI 依赖**（`package.json` 仍然只有 vue / vue-router / axios）：设计系统集中在 `frontend/src/style.css`
  （CSS 变量 + 浅色/深色两套 + 全部组件类），另有 5 个可复用组件（`AppIcon` / `AppEmpty` / `AppPager` / `AppDrawer` / `StatusTag`）
  和全局 `toast` / `confirmDialog`（`src/ui.js`）。约定见 **`frontend/前端开发约定.md`**。
- ⭐ **零依赖的浏览器冒烟测试** `frontend/scripts/browser-smoke.mjs`：用系统已装的 Edge + CDP
  （Node 22+ 自带 `fetch` / `WebSocket`，**不装任何依赖、不下载浏览器**）。实测 **17/17 通过**：
  未登录被重定向、真填表单登录、**登录后侧边栏立刻出现**（V-11 的专用守卫）、7 个受保护页面逐页渲染、刷新保持登录、
  页面异常 0 / `console.error` 0 / 失败请求 0。
- **许可证**：Apache License 2.0（`LICENSE` + `NOTICE` + POM 里的 `<licenses>`）。
  理由与备选（MIT / GPL / AGPL）的差别记在问题修复档案 K-04。
- **监控告警规则** `deploy/prometheus/alerts.yml`：4 组 15 条规则，把已有计数器变成可执行的告警，
  每条都标注阈值依据属于 **【实测】** 还是 **【推断】**。⚠️ **未在真实告警链路上验证过**（本机没有该栈）。
- 熔断器行为集成用例 `DbSourceCircuitBreakerIntegrationTest`（2 条，真实驱动 + 真实连接池，量出毫秒级对比）。
- 平台日志表的保留策略（`retention.*`，**默认关闭**）：`export_task` / `audit_log` / `query_history`
  可按保留天数分批清理，指标 `retention.deleted` / `retention.failed`。
  ⚠️ 只删不归档；保留天数 ≤ 0 的项会被跳过并告警。
- 可配置的业务库时区 `datasource.mysql.server-timezone`（默认 `Asia/Shanghai`，
  环境变量 `MYSQL_SERVER_TIMEZONE`）。此前该值写死在代码里。
- NL2SQL 并发上限 `platform.llm.max-concurrent`（默认 4）+ `platform.llm.acquire-timeout-ms`（默认 2000）。
- 登录密码的 **BCrypt 成本因子可配**：`platform.password.bcrypt-strength`（默认 `10` = 原默认值，
  环境变量 `BCRYPT_STRENGTH`）。**此前没有这个配置项** —— 编码器是 `new BCryptPasswordEncoder()`，
  轮次由库的默认值决定，代码和配置里都看不见。允许范围 `[4, 16]`，超出夹住 + WARN；
  启动时把生效值打进日志。新增计数器 `password.rehash{outcome=success|failed}`。
- 新增规范文件：`.editorconfig`、`.gitattributes`、`.dockerignore`、`docs/开发规范.md`、本文件。
- Maven Enforcer：构建时校验 JDK ≥ 17、Maven ≥ 3.9、**依赖版本收敛**。

### 变更
- **`redis_subscriber_up` 的语义写清楚了**（实测发现原注释有误导）：它跟踪的是 **Spring 订阅容器**
  是否在运行，**不是 Redis 是否可达** —— `docker stop` 掉 Redis 后 Lettuce 会无限重连、
  容器始终 running，这个指标全程是 1。**Redis 挂了要看 `RedisDegraded`。**
  `deploy/prometheus/alerts.yml` 里两条规则的 description 已按实测改写。
- **告警规则首次真机验证**（第八轮，**2 条**）：`promtool check rules` → `SUCCESS: 15 rules found`；
  停 Redis 触发 `RedisDegraded`；用「应用带 Redis 故障启动」触发 `RedisSubscriberDown` ——
  pending → firing **125 秒**（= `for: 2m` + 一个 5 秒求值步长），恢复后 **30.4 秒** 指标归零、**36.5 秒** inactive。
  另外 13 条仍未做故障注入验证；未接 Alertmanager，通知链路未验证。
- ⚠️ 监控的一个固有限制被实测出来：**Micrometer Counter 首次自增才注册** —— 没触发过的计数器
  在 `/actuator/prometheus` 里根本不存在（本轮实测 89 个指标族里只有 `redis_subscriber_up` 在）。
  对 `increase(...) > 0` 类规则不影响正确性（空结果 = inactive），但**「没有数据」与「真的是 0」
  在告警状态上分不出来**。
- 容器里跑 Prometheus 抓本机应用时，**必须设 `MANAGEMENT_ADDRESS=0.0.0.0`**
  （默认 127.0.0.1 只监听容器自己的回环，抓不到）—— 这是上一轮把它做成可配的直接价值。
- **审计日志改为按结果记录**（`@Before` → `@Around`），并在 `detail` 里带上 `[SUCCESS]` / `[FAILED:异常类名]`。
  被并发闸门 / 熔断拒绝的请求（`RejectedBeforeWorkException`）**不再产生审计写**，
  改记 `audit.skipped{reason=rejected-before-work}`（这修掉了压测里那条负向扩展）。
- `POST /api/export` 现在会等 Broker 投递确认（`export.publish-confirm-timeout-ms`，默认 3000ms）：
  等不到时任务标 `FAILED` 并返回业务失败「导出任务创建失败，请稍后重试」，
  不再留下永远 PENDING 的孤儿行。
- 工单补偿重投同样等确认（`review.recovery-publish-confirm-timeout-ms`，默认 5000ms）：
  未确认就不刷新 `updated_at`，避免重投被一轮轮推迟。
- **`amqp-client` 的版本覆盖被删除**：不再固定在 5.35.0，回到 Spring Boot 4.1.0 管理的 **5.30.0**。
  理由：那个覆盖**拿不出原因**（无 CVE、无对应 issue），而 Boot 的测试矩阵就是围绕 5.30.0 做的；
  删掉之后升级 Boot 会自动跟随，也少一处「不敢碰、也说不清」的配置。
  （中间那一步 —— 把覆盖位置从直接依赖改到 `<dependencyManagement>` —— 见下方「工程」一节）
- `amqp-client` 的版本覆盖从「直接依赖上写 `<version>`」改为**写进 `<dependencyManagement>`**：
  同样的 5.35.0，但依赖树里不再同时存在两个版本（此前 `dependencyConvergence` 会失败）。
- 监控端口的监听地址改为可配（`MANAGEMENT_ADDRESS`，默认仍是 `127.0.0.1`）：
  容器 / K8s 探针需要时不必再改源码。
- `management.server.address`、审计、限流等既有行为**均未改变对外契约**（HTTP 200 + 业务 code 不变）。

### 修复
- ⭐ **主源码 lint 警告清零，并把这条线搬进构建**：`default-compile` 现在带
  `-Xlint:all,-processing,-classfile`。起因是 `SqlValidateUtil` 里两处 JSqlParser 4.9 的
  `@Deprecated` 调用（`TablesNamesFinder.getTableList` / `Statements.getStatements`）——
  它们**只在 IDE 里挂黄，命令行构建完全看不见**；`getTableList` 的实现就是
  `new ArrayList<>(getTables(...))`（反编译确认），所以自己包一层等价、行为不变。
  打开 lint 后又抓出 6 条同类问题，一起修掉：① `SqlImpactAnalyzer` 两处 `JsonNode.fields()`
  （已弃用）→ `properties()`；② `DbSourceServiceImpl` 的"资源 `connection` 在 try 体内从未被引用"
  → 就地抑制并写明理由（那是误报：连接对象的用途就是"建得起来"，Java 9 的"无变量资源"语法只接受变量、
  不接受方法调用）；③ 四个可序列化异常补 `serialVersionUID`。
  ⚠️ 唯一保留的例外：`RabbitConfig` 的 `Jackson2JsonMessageConverter`（Spring AMQP 4.1 标记 `forRemoval`，
  替代品基于 Jackson 3，换过去要同时引入两套 JSON 栈并改消息体线上格式 —— 登记在案，需单独一轮 + MQ 集成测试）。
  不用 `-Werror`：JDK 升级可能新增 lint 类别，不该因此让构建直接失败。
- ⭐ **编辑数据源时「密码留空 = 不修改」以前根本做不到**：`DbSourceDTO.password` 带 `@NotBlank`（新增和编辑共用同一个 DTO），
  而 `updateDbSource` 又无条件用请求里的密码覆盖密文 —— 而出参**永不下发密码**，所以编辑框天然是空的：
  结果是「只想改个名字，也必须重新输一遍数据库密码」；更糟的是密码框被清空又过了校验时，
  数据源会被改成「密码为空」，下次连接必然失败（报错和"密码填错"一模一样）。
  现在：`password` 去掉 `@NotBlank`，「新增必填」下沉到 `addDbSource`；`updateDbSource` 留空则回填**库里那条密文**、
  填了才重新加密覆盖；顺带删掉 `toEntity` 里对密码字段的赋值（那里以前放的是明文，忘了覆盖就会落库）。
  新增 `DbSourcePasswordUpdateTest`（4 条，含反向验证）。
- ⭐ **导出的「时间预算」三处口径互相矛盾，已对齐**：`application.yml` 的 `stuck-running-minutes` 是 10，
  而「最坏执行耗时」是 `max-attempts(3) × query-timeout-seconds(300) = 900` 秒 ——
  后果有两条：①**每次启动都打一条 ERROR**；②一条接近超时的导出会在第 10 分钟被补偿任务判成卡死，
  而消费者还在重试，跑完后又因认领令牌失配写不回结果（用户看到的状态前后矛盾）。
  现在阈值改为 **20**（1200 秒），yml 注释改成算完整的账，`ExportConsumer` 里那句「默认 10 分钟」的注释也已订正。
  新增 `ExportTimeBudgetConfigTest`：**直接解析 `application.yml`** 断言这条不变式 ——
  「不变式只写在启动日志里」等于没写（日志会被刷过去），测试才拦得住。
  同一测试还要求 `export.tmp-retention-hours` 必须显式写在 yml 里（此前只存在于 `@Value` 默认值）。
- ⭐ **合并写：一次查询请求的主库提交从 2 次降到 1 次**（还清档案 G-08 记的那笔债）：
  审计与查询历史由请求级写缓冲收集、在**一个事务**里提交（用保存点保证两条写互不牵连）。
  实测：一次请求 = 一次合并提交、提交里带两条写；反向验证：改回直接提交 → `expected: <2> but was: <1>`。
  新增指标 `platform.write.flush` / `platform.write.flushed{outcome}`（合并是否真的在工作，生产可观测）。
  ⚠️ 代价：写被推迟到请求结束（毫秒级窗口）；缓冲只在当前线程有效（定时任务/MQ 消费者直接写库）。
- ⭐ **数据源连不上时熔断器不再「不知道」**：连接池创建失败抛的是 HikariCP 的
  `PoolInitializationException`（RuntimeException），它会穿过原先只接 `BusinessException` 的 catch ——
  后果是①**熔断器永远不打开**（每次请求都重新等满 5 秒建连超时）②用户拿到的是「系统异常」。
  现在这类失败也会交给熔断器判定，并给用户一句「该数据源当前不可用（连接失败）」。
  实测：建连超时 6035 ms → 熔断后被拒绝 0 ms；反向验证时两条用例同时变红。
- **ack 抛异常不再被当成业务失败**：两个消费者改用 `ack()` / `nackToDlq()` 封装，
  异常只记 `mq.ack.failed{link}` + WARN。此前一次**已经成功**的工单执行会被报成「结果未能落库」
  并进死信队列，导出则会被重跑一遍。
- **投递成功不再只看 `convertAndSend` 有没有抛异常**：队列满时 Broker 是异步否定确认，
  新增 `RabbitPublishConfirmer` 主动等确认，记 `mq.publish.unconfirmed{link}`。
- 导出半成品文件改为「先写 `.tmp` 再原子改名」，并新增过期 `.tmp` 清理。
- 补偿任务加分批上限、逐行异常隔离、以及启动时的「时间预算」自检。
- 导出 CSV 按 JDBC 类型序列化（binary/blob → Base64、clob → 字符串）并防公式注入。
- MySQL `socketTimeout` / `connectTimeout` 补齐；`server.shutdown: graceful`；调度线程池 4 → 6。

### 安全
- **停用的账号不再能登录**：`login` 在此之前**完全不读** `user.status` —— 运维把某个账号的 `status`
  改成 0，本人照样登录、照样拿到 token（「停用账号」只存在于数据模型和响应字段里）。
  现在只认「**明确等于 0**」：`status` 为 `NULL` 的历史行按启用处理，避免一次上线把老账号锁在门外。
  校验放在密码校验**之后**（放前面会变成拿任意密码就能探测账号是否被停用）。
- **账号维度的登录限流不再能被大小写绕过**：限流 key 原先直接拼原始用户名，而库的排序规则是
  `utf8mb4_unicode_ci`（`admin` / `Admin` / `ADMIN` 是同一行用户）、Redis 的 key 是大小写敏感的 ——
  换个写法就是换一个桶，「单账号 10 次/分钟」可以被放大成 10 × 大小写变体数。
  现在 key 用 `trim().toLowerCase(Locale.ROOT)` 归一化（带 `Locale` 是因为默认区域在土耳其语下会把 `I` 变成 `ı`）。
- 业务库连接串的时区参数做了字符白名单校验（含 `&` / `=` 的值一律忽略）——
  该值会被直接拼进 JDBC URL，一个 `&` 就能塞进任意驱动参数。
- **老密码哈希改为「登录成功时渐进升级」**：BCrypt 的 cost 写在每条哈希串里，所以调高成本因子
  既不会让老用户登录失败，也**不会**让他们的哈希自动升级 —— 唯一能拿到明文的时刻就是登录成功那一刻。
  现在登录校验通过后比对 `upgradeEncoding(旧哈希)`，需要升级就把密码列**原地重算**；
  升级走**条件更新**（`WHERE id=? AND password=<读到的旧哈希>`），避免并发登录 / 重置密码期间
  把刚改好的新密码覆盖回旧的；**升级失败不影响登录**（只记 `password.rehash{outcome=failed}` + WARN）。
  ⚠️ 边界：只升级 cost，**不换算法**（`$2a$`/`$2b$`/`$2y$` 前缀差异未处理）。

## 版本说明

| 版本 | 说明 |
|---|---|
| `0.0.1-SNAPSHOT` | 当前开发版本。尚未发布过正式版本，因此上面所有条目都归在 `Unreleased` ——
 **不臆造版本号**：正式发版时应由作者决定版本号并打 tag。|

