package com.example.sqlquery.config;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.ConnectionManager;
import com.example.sqlquery.util.JdbcUrlUtil;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.sql.*;
import java.time.LocalDateTime;

@Component
public class ExportConsumer {

    private final ExportTaskMapper exportTaskMapper;
    private final DbSourceService dbSourceService;
    private final ConnectionManager connectionManager;
    private final AesUtil aesUtil;

    public ExportConsumer(ExportTaskMapper exportTaskMapper, DbSourceService dbSourceService, AesUtil aesUtil, ConnectionManager connectionManager) {
        this.exportTaskMapper = exportTaskMapper;
        this.dbSourceService = dbSourceService;
        this.aesUtil = aesUtil;
        this.connectionManager = connectionManager;
    }

    @RabbitListener(queues = RabbitConfig.EXPORT_QUEUE)
    public void handle(Long taskId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        task.setStatus("RUNNING");
        exportTaskMapper.updateById(task);

        try {
            DbSource dbSource = dbSourceService.getById(task.getDataSourceId());
            if (dbSource == null) {
                throw new BusinessException("数据源不存在");
            }

            File exportDir = new File("exports");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            File file = new File(exportDir, taskId + ".csv");

            long rowCount = generateCsv(dbSource, task.getSqlText(), file);

            task.setStatus("SUCCESS");
            task.setFilePath(file.getPath());
            task.setRowCount(rowCount);
            task.setFinishedAt(LocalDateTime.now());
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
        }

        exportTaskMapper.updateById(task);
    }

    private long generateCsv(DbSource dbSource, String sql, File file) throws Exception {
        String url = JdbcUrlUtil.build(dbSource);
        long rowCount = 0;

        try (Connection connection = connectionManager.getConnection(dbSource);
                
                
                
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql);
             FileWriter writer = new FileWriter(file)) {

            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    writer.write(",");
                }
                writer.write(csvEscape(metaData.getColumnLabel(i)));
            }
            writer.write("\n");

            while (resultSet.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        writer.write(",");
                    }
                    Object value = resultSet.getObject(i);
                    writer.write(csvEscape(value == null ? "" : value.toString()));
                }
                writer.write("\n");
                rowCount++;
            }
        }

        return rowCount;
    }

    private String csvEscape(String value){
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
