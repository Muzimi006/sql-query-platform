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
- 平台日志表的保留策略（`retention.*`，**默认关闭**）：`export_task` / `audit_log` / `query_history`
  可按保留天数分批清理，指标 `retention.deleted` / `retention.failed`。
  ⚠️ 只删不归档；保留天数 ≤ 0 的项会被跳过并告警。
- 可配置的业务库时区 `datasource.mysql.server-timezone`（默认 `Asia/Shanghai`，
  环境变量 `MYSQL_SERVER_TIMEZONE`）。此前该值写死在代码里。
- NL2SQL 并发上限 `platform.llm.max-concurrent`（默认 4）+ `platform.llm.acquire-timeout-ms`（默认 2000）。
- 新增规范文件：`.editorconfig`、`.gitattributes`、`.dockerignore`、`docs/开发规范.md`、本文件。
- Maven Enforcer：构建时校验 JDK ≥ 17、Maven ≥ 3.9、**依赖版本收敛**。

### 变更
- **审计日志改为按结果记录**（`@Before` → `@Around`），并在 `detail` 里带上 `[SUCCESS]` / `[FAILED:异常类名]`。
  被并发闸门 / 熔断拒绝的请求（`RejectedBeforeWorkException`）**不再产生审计写**，
  改记 `audit.skipped{reason=rejected-before-work}`（这修掉了压测里那条负向扩展）。
- `POST /api/export` 现在会等 Broker 投递确认（`export.publish-confirm-timeout-ms`，默认 3000ms）：
  等不到时任务标 `FAILED` 并返回业务失败「导出任务创建失败，请稍后重试」，
  不再留下永远 PENDING 的孤儿行。
- 工单补偿重投同样等确认（`review.recovery-publish-confirm-timeout-ms`，默认 5000ms）：
  未确认就不刷新 `updated_at`，避免重投被一轮轮推迟。
- `amqp-client` 的版本覆盖从「直接依赖上写 `<version>`」改为**写进 `<dependencyManagement>`**：
  同样的 5.35.0，但依赖树里不再同时存在两个版本（此前 `dependencyConvergence` 会失败）。
- 监控端口的监听地址改为可配（`MANAGEMENT_ADDRESS`，默认仍是 `127.0.0.1`）：
  容器 / K8s 探针需要时不必再改源码。
- `management.server.address`、审计、限流等既有行为**均未改变对外契约**（HTTP 200 + 业务 code 不变）。

### 修复
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
- 业务库连接串的时区参数做了字符白名单校验（含 `&` / `=` 的值一律忽略）——
  该值会被直接拼进 JDBC URL，一个 `&` 就能塞进任意驱动参数。

## 版本说明

| 版本 | 说明 |
|---|---|
| `0.0.1-SNAPSHOT` | 当前开发版本。尚未发布过正式版本，因此上面所有条目都归在 `Unreleased` ——
 **不臆造版本号**：正式发版时应由作者决定版本号并打 tag。|

