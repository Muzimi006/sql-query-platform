package com.example.sqlquery.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExportTaskVO {

    private Long id;

    private Long dataSourceId;

    private String sqlText;

    private String status;

    /**
     * 是否还能下载。
     *
     * <p>刻意不下发服务器上的文件路径 —— 那是部署细节，对客户端没有意义，
     * 而且会把服务器目录结构暴露出去。客户端需要知道的只有"能不能下"，
     * 真的下载走 {@code GET /api/export/{id}/download}。
     *
     * <p>取值只看数据库：任务成功 <b>且</b> {@code file_path} 还在。
     * 清理任务删文件时会把 {@code file_path} 一并置空，所以这里不需要做文件系统判断。
     */
    private Boolean downloadable;

    private Long rowCount;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
