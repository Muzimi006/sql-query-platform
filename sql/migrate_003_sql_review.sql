-- ============================================================
-- 迁移 003：SQL 审核工单表
-- ============================================================
-- 背景
--   项目名字叫「SQL 查询与审核平台」，但改造前只有"查询"和"拦截"，
--   **没有任何"审核"**：把角色权限放开之后，ADMIN 可以立即执行 UPDATE，
--   没有审批、没有影响面评估、没有留痕。
--
--   票务 / 文物这类业务里，改一条数据就是钱和档案的问题，
--   "能不能执行"（权限）和"该不该批准"（审核）是两件事。
--
-- 本脚本做什么
--   建 sql_review_request 表：写语句不再立即执行，而是先落一条待审工单。
--
-- 幂等性
--   CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================

USE sql_query_platform;

CREATE TABLE IF NOT EXISTS sql_review_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,

  user_id BIGINT NOT NULL COMMENT '申请人',
  data_source_id BIGINT NOT NULL COMMENT '目标数据源',

  -- ⚠️ 执行时以这一列存的 SQL 为准，绝不接受前端再传一次。
  -- 否则会出现"批准的是一条、执行的是另一条"（TOCTOU）。
  sql_text TEXT NOT NULL,
  sql_type VARCHAR(20) NOT NULL COMMENT 'INSERT/UPDATE/DELETE/DDL',

  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
      COMMENT 'PENDING/APPROVED/REJECTED/EXECUTING/EXECUTED/FAILED',

  -- ---- 影响面：提交时算好，给审批人看 ----
  involved_tables VARCHAR(500) COMMENT 'AST 解析出的表名，逗号分隔',
  estimated_rows BIGINT COMMENT 'EXPLAIN 预估扫描行数（注意：不是影响行数）',
  estimate_note VARCHAR(200) COMMENT '预估的补充说明，例如"全表扫描"',

  -- ---- 来源：手写还是 AI 生成 ----
  generated_by VARCHAR(20) NOT NULL DEFAULT 'HUMAN' COMMENT 'HUMAN/AI',
  generation_prompt TEXT COMMENT 'AI 生成时用户的原始自然语言问题',

  -- ---- 幂等键：客户端重复提交（比如双击、网络重试）不会产生两条工单 ----
  -- 允许为 NULL，MySQL 的唯一索引允许多个 NULL，所以不传就不去重
  request_token VARCHAR(64) COMMENT '客户端幂等键，可选',
  UNIQUE KEY uk_user_token (user_id, request_token),

  -- ---- 审批 ----
  reviewer_id BIGINT,
  review_comment VARCHAR(500),
  reviewed_at DATETIME,

  -- ---- 执行 ----
  affected_rows BIGINT COMMENT '实际影响行数',
  error_message TEXT,
  executed_at DATETIME,

  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  -- 申请人看自己的工单
  KEY idx_user_status (user_id, status),
  -- 审批人看"待我审批"的，以及补偿任务扫卡死的
  KEY idx_status_created (status, created_at)
);

-- 执行后验收：应该能看到表结构
-- SHOW CREATE TABLE sql_review_request;
