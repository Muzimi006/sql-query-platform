package com.example.sqlquery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条工单执行的「前镜像 + 反向 SQL」留痕。
 *
 * <p>它补的是 L6：在它之前，平台对一条已经生效的 UPDATE 能说的只有
 * {@code affected_rows} 和 {@code error_message} —— <b>回滚依据一点都没留</b>。
 *
 * <p>⚠️ 主键<b>就是</b> {@code reviewId}，不是自增 id：
 * 一条工单只该有一笔留存记录。用自增主键的话，重投/重试会不断追加新行，
 * 于是「这条工单到底哪笔才算数」变成一个要看时间戳猜的问题。
 * 用工单 id 做主键，重复写入会被数据库直接挡住（或走 {@code replace} 覆盖）。
 */
@Data
@TableName("sql_review_undo")
public class SqlReviewUndo {

    @TableId(type = IdType.INPUT)
    private Long reviewId;

    /** 见 {@link com.example.sqlquery.domain.UndoStatus}。 */
    private String captureStatus;

    /** 没留存下来时，为什么 —— 这句话直接展示给管理员。 */
    private String reason;

    private String tableName;

    private Integer rowCount;

    /** 前镜像：JSON 数组，每行一个对象。 */
    private String beforeJson;

    /** 后镜像：执行后按主键重新查出来的同一批行。 */
    private String afterJson;

    /** 由前镜像生成的反向 SQL，供人工核对后作为新工单提交。 */
    private String inverseSql;

    private LocalDateTime createdAt;
}