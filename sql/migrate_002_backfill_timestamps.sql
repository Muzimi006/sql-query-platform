-- ============================================================
-- 迁移 002：回填被写成 NULL 的时间字段
-- ============================================================
-- 背景
--   MyBatis-Plus 在 Spring Boot 4 下自动配置退让（原因见 MybatisPlusConfig 的注释），
--   而手工装配 SqlSessionFactory 时漏掉了 GlobalConfig 里的 MetaObjectHandler。
--   结果是 @TableField(fill = FieldFill.INSERT) 标注的列会被**显式写成 NULL** ——
--   连库表的 DEFAULT CURRENT_TIMESTAMP 也一起被顶掉。
--
--   代码已经修好了（见 MybatisPlusConfig + MyMetaObjectHandler），
--   但**已经写坏的历史行不会自己变好**，必须执行本脚本。
--
-- 本脚本做什么
--   把 created_at / updated_at 为 NULL 的行回填成一个近似值。
--
-- ⚠️ 回填的是近似值，不是真实值
--   真实创建时间没有任何留痕，无法还原。取值优先级：
--     ① 同一行里另一个已知时间（export_task.finished_at、data_source/user.updated_at）
--     ② 都没有时才用 NOW()
--   如果这些时间对你的统计有实际意义（而不是只为让排序不乱），
--   正确做法是删掉这些行重建，而不是接受一个假时间。
--
-- 幂等性
--   每条语句都带 WHERE ... IS NULL，重复执行不会改动已经回填过的行。
--
-- 对应代码改动：MybatisPlusConfig / MyMetaObjectHandler
-- ============================================================

USE sql_query_platform;

-- ---------- 执行前：先看清有多少行要动 ----------
SELECT '执行前' AS stage, 'data_source' AS tbl,
       COUNT(*) AS total, SUM(created_at IS NULL) AS null_created, SUM(updated_at IS NULL) AS null_updated
FROM data_source
UNION ALL SELECT '执行前', 'user', COUNT(*), SUM(created_at IS NULL), SUM(updated_at IS NULL) FROM user
UNION ALL SELECT '执行前', 'export_task', COUNT(*), SUM(created_at IS NULL), NULL FROM export_task
UNION ALL SELECT '执行前', 'query_history', COUNT(*), SUM(created_at IS NULL), NULL FROM query_history
UNION ALL SELECT '执行前', 'favorite_sql', COUNT(*), SUM(created_at IS NULL), NULL FROM favorite_sql;

-- ---------- 回填 ----------
-- 先处理 created_at：能从同行其它时间列借到就用，借不到才用 NOW()
-- 注意顺序：必须先把 created_at 补上，下一步的 updated_at 才有值可借，
-- 否则两个都为 NULL 的行会互相借不到。
UPDATE data_source SET created_at = COALESCE(updated_at, NOW()) WHERE created_at IS NULL;
UPDATE user        SET created_at = COALESCE(updated_at, NOW()) WHERE created_at IS NULL;
-- export_task 的 finished_at 是同一行里最接近创建时间的已知值
-- （任务从创建到完成只隔几秒），比 NOW() 准确得多
UPDATE export_task SET created_at = COALESCE(finished_at, NOW()) WHERE created_at IS NULL;
-- query_history / favorite_sql 没有第二个时间列可借，只能用 NOW()
UPDATE query_history SET created_at = NOW() WHERE created_at IS NULL;
UPDATE favorite_sql  SET created_at = NOW() WHERE created_at IS NULL;

-- updated_at 从已经补好的 created_at 借
UPDATE data_source SET updated_at = created_at WHERE updated_at IS NULL;
UPDATE user        SET updated_at = created_at WHERE updated_at IS NULL;

-- ---------- 执行后：验收 ----------
-- 期望：所有 null_created / null_updated 都是 0
SELECT '执行后' AS stage, 'data_source' AS tbl,
       COUNT(*) AS total, SUM(created_at IS NULL) AS null_created, SUM(updated_at IS NULL) AS null_updated
FROM data_source
UNION ALL SELECT '执行后', 'user', COUNT(*), SUM(created_at IS NULL), SUM(updated_at IS NULL) FROM user
UNION ALL SELECT '执行后', 'export_task', COUNT(*), SUM(created_at IS NULL), NULL FROM export_task
UNION ALL SELECT '执行后', 'query_history', COUNT(*), SUM(created_at IS NULL), NULL FROM query_history
UNION ALL SELECT '执行后', 'favorite_sql', COUNT(*), SUM(created_at IS NULL), NULL FROM favorite_sql;
