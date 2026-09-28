-- SQL 查询与审核平台 数据库初始化脚本

CREATE DATABASE IF NOT EXISTS sql_query_platform
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE sql_query_platform;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  nickname VARCHAR(50),
  role VARCHAR(20) DEFAULT 'USER',
  status TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS data_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  type VARCHAR(20) NOT NULL COMMENT 'mysql/opengauss',
  host VARCHAR(100) NOT NULL,
  port INT NOT NULL,
  database_name VARCHAR(100),
  username VARCHAR(100) NOT NULL,
  password_encrypted VARCHAR(500) NOT NULL,
  status TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- 同一用户下数据源名称唯一。应用层的 checkNameUnique 是「先查再插」，
  -- 并发下两个请求可能同时查到「不重名」—— 唯一索引才是真正的兜底。
  -- 注意列顺序是 (user_id, name)：查询都是「按用户查」，最左前缀必须能用上。
  -- 唯一索引的最左前缀就是 user_id，所以不再需要单独的 idx_user_id（冗余索引会拖慢写入）。
  UNIQUE KEY uk_user_id_name (user_id, name)
);

CREATE TABLE IF NOT EXISTS query_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  data_source_id BIGINT NOT NULL,
  sql_text TEXT NOT NULL,
  status VARCHAR(20) COMMENT 'SUCCESS/FAILED',
  cost_ms BIGINT,
  error_message TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_id_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS favorite_sql (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  data_source_id BIGINT,
  sql_text TEXT NOT NULL,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS export_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  data_source_id BIGINT NOT NULL,
  sql_text TEXT NOT NULL,
  file_path VARCHAR(500),
  status VARCHAR(20) COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  row_count BIGINT,
  error_message TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME,
  KEY idx_user_id (user_id)
);


CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  action VARCHAR(50),
  detail VARCHAR(500),
  ip VARCHAR(50),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_id (user_id)
);

-- ============================================================
-- 已经建过库的环境需要执行迁移，见同目录：
--   migrate_001_data_source_name_unique.sql
--
-- 内容：检查重复数据 → 加唯一索引 uk_user_id_name
--       → 删除被覆盖的冗余索引 idx_user_id → 验收
--
-- 该脚本是幂等的（加过就跳过、不存在就跳过），可重复执行。
-- 不要把 ALTER 语句直接写在这里 —— 新建库上执行会因为
-- 「索引已存在 / 索引不存在」而报错，让 schema.sql 不再是一次性可跑的脚本。
-- ============================================================

CREATE USER IF NOT EXISTS 'query_user'@'%' IDENTIFIED BY 'QueryUser123456';

GRANT SELECT ON sql_query_platform.* TO 'query_user'@'%';

FLUSH PRIVILEGES;