-- ============================================================
-- 迁移 004：export_task 增加「被认领的时刻」
-- ============================================================
-- 背景
--   导出任务的卡死判定原本用的是 created_at，而 created_at 是**任务创建**的时刻 ——
--   它把"在队列里排队的时间"也算进去了。
--
--   这会导致一个真实的误判：队列积压时，
--   「排队 6 分钟 + 执行 5 分钟」的**正常任务**会被判成卡死（阈值 10 分钟），
--   补偿任务把它标成 FAILED；而消费者随后跑完又把状态写回 SUCCESS，
--   于是用户看到"导出成功"、点进去却有一句"已由补偿任务标记为失败"。
--
--   正确的判定依据是**"认领之后过了多久"**，不是"创建之后过了多久"。
--   所以加一列 started_at，由消费者在条件认领（PENDING → RUNNING）时写入。
--
-- 幂等性
--   MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，所以用 information_schema 判断后动态执行。
--   回填语句也带 IS NULL 条件，可重复执行。
-- ============================================================

USE sql_query_platform;

-- ---- 1. 加列（已存在则跳过）----
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'export_task'
      AND column_name = 'started_at'
);

SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE export_task ADD COLUMN started_at DATETIME NULL COMMENT ''被消费者认领的时刻（PENDING → RUNNING 时写入）'' AFTER status',
    'SELECT ''started_at 已存在，跳过加列'' AS skipped');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---- 2. 回填历史数据 ----
-- ⚠️ 这一步不是可有可无的：卡死判定的条件是 started_at < 阈值，
--    如果历史行留着 NULL，它们**永远不会被判定**，会一直卡在 RUNNING 没人管。
--    对已经结束的任务，started_at 填 created_at 只是个近似（没有更准的来源了），
--    对补偿任务没有影响（它们已经不是 RUNNING 了）。
UPDATE export_task
SET started_at = created_at
WHERE started_at IS NULL
  AND status <> 'PENDING';

-- ---- 3. 给补偿任务的查询加索引 ----
-- 补偿任务的条件是 status = 'RUNNING' AND started_at < ?。
-- RUNNING 的行数正常情况下是 0，所以单列 status 的索引已经够用；
-- 这里不再额外建 (status, started_at) 组合索引 —— 为一个只扫几行的查询加索引是净亏。

-- 执行后验收：应该能看到 started_at 列，且 RUNNING 行都有值
-- SHOW COLUMNS FROM export_task LIKE 'started_at';
-- SELECT status, COUNT(*) total, SUM(started_at IS NULL) null_started FROM export_task GROUP BY status;
