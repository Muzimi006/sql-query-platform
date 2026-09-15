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
  KEY idx_user_id (user_id)
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