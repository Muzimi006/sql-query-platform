-- ============================================================================
-- 迁移 001：data_source 增加「同一用户下名称唯一」约束
--
-- 背景：数据源名称查重原先只有应用层的 checkNameUnique，它是「先 count 再 insert」，
-- 并发下两个请求可能同时 count 到 0，然后双双插入成功。数据库侧没有任何兜底。
--
-- 新建库不用执行本脚本 —— schema.sql 里已经带上了 UNIQUE KEY uk_user_id_name。
-- 本脚本只针对「已经建过表」的环境。
--
-- 特性：
--   * 可重复执行（幂等）—— 已经加过就跳过，不存在就加
--   * 索引名写在字符串里，避免部分 IDE / SQL 客户端做静态解析时报
--     「无法解析索引 'idx_user_id'」这类误报
--
-- 执行方式：先整段看一下，再逐段执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第 0 步：确认连的是哪个库（务必是 sql_query_platform）
-- ---------------------------------------------------------------------------
SELECT DATABASE() AS current_database;

-- ---------------------------------------------------------------------------
-- 第 1 步：看现在有哪些索引，心里有数
-- ---------------------------------------------------------------------------
SELECT INDEX_NAME,
       IF(NON_UNIQUE = 0, '唯一', '普通') AS index_type,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'data_source'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- ---------------------------------------------------------------------------
-- 第 2 步：检查重复数据
-- ⚠️ 只要有输出，就必须先清理，否则第 3 步加唯一索引会直接失败
-- ---------------------------------------------------------------------------
SELECT user_id, name, COUNT(*) AS duplicate_count
FROM data_source
GROUP BY user_id, name
HAVING duplicate_count > 1;

-- 清理示例（确认要保留哪一条再执行，别直接跑）：
--   DELETE d1 FROM data_source d1
--   JOIN data_source d2
--     ON d1.user_id = d2.user_id AND d1.name = d2.name AND d1.id > d2.id;

-- ---------------------------------------------------------------------------
-- 第 3 步：加唯一索引（不存在才加）
-- ---------------------------------------------------------------------------
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_source'
      AND INDEX_NAME = 'uk_user_id_name'
);
SET @stmt := IF(@idx_exists = 0,
    'ALTER TABLE data_source ADD UNIQUE KEY uk_user_id_name (user_id, name)',
    'SELECT ''uk_user_id_name 已存在，跳过'' AS result');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 第 4 步：删掉被覆盖的冗余索引 idx_user_id（存在才删）
-- uk_user_id_name 的最左前缀就是 user_id，单独的 idx_user_id 变冗余 ——
-- 冗余索引不提升查询，只会拖慢写入并误导优化器。
-- ---------------------------------------------------------------------------
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_source'
      AND INDEX_NAME = 'idx_user_id'
);
SET @stmt := IF(@idx_exists > 0,
    'ALTER TABLE data_source DROP INDEX idx_user_id',
    'SELECT ''idx_user_id 不存在，跳过'' AS result');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 第 5 步：验收 —— 应该能看到 uk_user_id_name（唯一），且看不到 idx_user_id
-- ---------------------------------------------------------------------------
SELECT INDEX_NAME,
       IF(NON_UNIQUE = 0, '唯一', '普通') AS index_type,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'data_source'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 再验一次行为（第二次应该报 Duplicate entry）：
--   INSERT INTO data_source (user_id, name, type, host, port, username, password_encrypted)
--   VALUES (1, '迁移验收用', 'MySQL', 'localhost', 3306, 'u', 'v1:x');
--   INSERT INTO data_source (user_id, name, type, host, port, username, password_encrypted)
--   VALUES (1, '迁移验收用', 'MySQL', 'localhost', 3306, 'u', 'v1:x');   -- 预期报唯一键冲突
--   DELETE FROM data_source WHERE name = '迁移验收用';
