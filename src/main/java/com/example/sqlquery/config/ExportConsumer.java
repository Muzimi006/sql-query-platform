package com.example.sqlquery.config;

import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.AesUtil;
import com.example.sqlquery.util.ConnectionManager;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;

@Component
public class ExportConsumer {

    /** UTF-8 BOM，写在 CSV 文件最开头，让 Excel 正确识别编码。 */
    private static final char UTF8_BOM = '\uFEFF';

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
            // 消费者线程里没有 UserContext（不是 Web 线程），owner 从任务记录里取
            DbSource dbSource = dbSourceService.getByIdAndUserId(
                    task.getUserId(), task.getDataSourceId());

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

    /**
     * 把查询结果写成 CSV。
     *
     * <p>包级可见（而非 private）是为了让单元测试能直接调用它。
     *
     * <p>两个必须显式处理的点：
     * <ul>
     *   <li><b>必须显式指定 UTF-8</b>：{@code FileWriter} 用的是平台默认字符集 ——
     *       Windows 上是 GBK、Linux 上是 UTF-8，同一份代码在不同平台产出的文件不一样，
     *       而且本机测不出来；</li>
     *   <li><b>必须在开头写 UTF-8 BOM</b>：Excel 不会自动识别「没有 BOM 的 UTF-8」，
     *       不写 BOM 时中文照样乱码。BOM 只在文件最开头写一次。</li>
     * </ul>
     */
    long generateCsv(DbSource dbSource, String sql, File file) throws Exception {
        long rowCount = 0;

        try (Connection connection = connectionManager.getConnection(dbSource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql);
             OutputStreamWriter writer = new OutputStreamWriter(
                     new FileOutputStream(file), StandardCharsets.UTF_8)) {

            writer.write(UTF8_BOM);

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
