-- ============================================================
-- 迁移 005：工单写语句的「前镜像 + 反向 SQL」留存表
-- ============================================================
-- 背景
--   工单链路解决的最后一个问题是 L6：**执行失败 / 执行错了无法回滚**。
--   在此之前，平台对一条已经生效的 UPDATE 能说的只有两句话：
--   affected_rows 是多少、error_message 是什么。**回滚依据一点都没留。**
--
-- 本脚本做什么
--   建 sql_review_undo 表：一条工单一行，存执行前后的行镜像，
--   以及由前镜像生成的**反向 SQL**（供人核对后作为新工单提交）。
--
--   ⚠️ 它不提供「一键回滚」接口 —— 回滚本身是一条写语句，
--   必须走和其他写语句完全相同的审批路径（见 SqlUndoRecorder 的类注释）。
--
-- 幂等性
--   CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================

USE sql_query_platform;

CREATE TABLE IF NOT EXISTS sql_review_undo (
  -- 一条工单只留一笔：这张表是"工单执行留痕"的附属信息，不是流水账
  review_id BIGINT NOT NULL COMMENT '工单 id',

  -- CAPTURED / SKIPPED / FAILED 三种，语义完全不同，见 UndoStatus
  capture_status VARCHAR(20) NOT NULL COMMENT 'CAPTURED=已留存 / SKIPPED=形态不支持 / FAILED=取像出错',
  reason VARCHAR(300) COMMENT '没留存下来时，为什么 —— 这句话是给管理员看的',

  table_name VARCHAR(100) COMMENT '被改动的表（只有单表 UPDATE/DELETE 才可能留存）',
  row_count INT COMMENT '实际留存了多少行的前镜像',

  -- 前/后镜像：每行一个 JSON 对象，外层是数组。用 MEDIUMTEXT 是因为
  -- 上限 200 行 × 宽表可能到几百 KB；上限本身由 review.undo.max-rows 控制
  before_json MEDIUMTEXT COMMENT '前镜像（执行前的整行）',
  after_json  MEDIUMTEXT COMMENT '后镜像（执行后可定位到的同一批行）',

  -- ⭐ 反向 SQL：把前镜像翻译成人能读、也能直接当新工单提交的语句
  inverse_sql MEDIUMTEXT COMMENT '反向 SQL（由前镜像生成，供人工核对）',

  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单写语句的前后镜像与反向 SQL';

-- 执行后验收：
-- SHOW CREATE TABLE sql_review_undo;