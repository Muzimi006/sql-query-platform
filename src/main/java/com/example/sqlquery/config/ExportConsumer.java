package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.ConnectionManager;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * 导出任务消费者。
 *
 * <h2>三个必须做对的地方</h2>
 *
 * <p><b>一、重复投递必须幂等。</b>
 * MQ 保证的是"至少一次"投递，不是"恰好一次"。Broker 没收到 ack 就会重投，
 * 所以同一个 taskId 被消费两次是<b>正常情况</b>而不是异常。
 * 原来的实现只判断"任务存在不存在"，任务存在就重跑一遍 ——
 * 重复投递会重新查询、重新覆盖 CSV 文件，而查询本身可能有副作用（慢、占用连接）。
 * 现在改成用一个<b>条件更新</b>去"认领"任务（PENDING → RUNNING），
 * 更新影响行数为 0 就说明别人已经认领或已经做完了，直接跳过。
 *
 * <p>认领用 SQL 而不是"先查出状态再判断"：后者是"检查后行动"，
 * 两个消费者可能同时读到 PENDING，然后一起往下走 —— 竞态依然存在。
 * 条件更新把判断和写入合成了一条原子语句。
 *
 * <p><b>二、失败必须留日志。</b>
 * 原来 {@code catch (Exception e)} 只把消息写进 error_message，
 * 异常对象本身被丢掉了 —— 排查时看不到堆栈，只能看到一句数据库驱动的报错文本。
 *
 * <p><b>三、处理不掉的投递要进死信队列。</b>
 * 见 {@link #handle} 里 nack 的说明。
 */
@Slf4j
@Component
public class ExportConsumer {

    /** UTF-8 BOM，写在 CSV 文件最开头，让 Excel 正确识别编码。 */
    private static final char UTF8_BOM = '\uFEFF';

    private final ExportTaskMapper exportTaskMapper;
    private final DbSourceService dbSourceService;
    private final ConnectionManager connectionManager;

    public ExportConsumer(ExportTaskMapper exportTaskMapper, DbSourceService dbSourceService, ConnectionManager connectionManager) {
        this.exportTaskMapper = exportTaskMapper;
        this.dbSourceService = dbSourceService;
        this.connectionManager = connectionManager;
    }

    /**
     * 消费入口。
     *
     * <p>{@code ackMode = "MANUAL"} 是刻意的：自动 ack 在方法<b>返回时</b>确认，
     * 也就是说不管里面发生了什么（哪怕异常被 catch 吞掉）消息都会被确认掉。
     * 手工 ack 让"确认"成为一个我们要显式做出的决定。
     *
     * <p>确认策略：
     * <ul>
     *   <li>认领失败（重复投递）→ <b>ack</b>：这条消息已经没有任何工作要做，
     *       留在队列里只会被反复重投。</li>
     *   <li>执行完成，业务结果是 SUCCESS 或 FAILED → <b>ack</b>：
     *       消息被正确处理了。SQL 本身跑失败是业务结果，重投一万次也还是失败
     *       （而且会真的再跑一遍那条 SQL），所以不走重试。</li>
     *   <li>连"把结果写回数据库"都做不到（数据库不可用等）→ <b>nack 且不重新入队</b>
     *       → 消息进死信队列。这类失败是基础设施问题，就地重试只会空转。</li>
     * </ul>
     */
    @RabbitListener(queues = RabbitConfig.EXPORT_QUEUE, ackMode = "MANUAL")
    public void handle(Long taskId, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (!tryClaim(taskId)) {
                log.info("导出任务已被认领或已完成，跳过本次投递（幂等保护）。taskId={}", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            execute(taskId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("导出任务处理失败且结果未能落库，投递到死信队列。taskId={}, dlq={}",
                    taskId, RabbitConfig.EXPORT_DLQ, e);
            // requeue=false：直接进死信交换机。
            // 用 true 重投的话，如果是持续性故障（数据库连不上），
            // 消息会在队列里无限打转，把 CPU 和日志一起打满。
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 原子认领：只有当任务确实是 {@link ExportStatus#PENDING} 时才置为 RUNNING。
     *
     * <p>返回 {@code false} 的所有情况都表示"本次投递不该做任何事"：
     * 任务不存在、已经被别的消费者认领、已经成功、已经失败。
     */
    boolean tryClaim(Long taskId) {
        int affected = exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                .eq(ExportTask::getId, taskId)
                .eq(ExportTask::getStatus, ExportStatus.PENDING)
                .set(ExportTask::getStatus, ExportStatus.RUNNING));
        return affected == 1;
    }

    /**
     * 真正干活并把结果写回数据库。
     *
     * <p>业务失败不往外抛（写到 {@code error_message} 并置 FAILED 即可），
     * 只有<b>写库这一步本身失败</b>才会抛出去，交给 {@link #handle} 决定进死信队列。
     * 那时任务会停在 RUNNING，由 {@link ExportTaskRecoveryJob} 兜底。
     */
    void execute(Long taskId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            // 任务在处理过程中被用户删掉了，没有可写回的对象
            log.warn("导出任务不存在，跳过。taskId={}", taskId);
            return;
        }

        File file = null;
        try {
            // 消费者线程里没有 UserContext（不是 Web 线程），owner 从任务记录里取
            DbSource dbSource = dbSourceService.getByIdAndUserId(
                    task.getUserId(), task.getDataSourceId());

            File exportDir = new File("exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("导出目录创建失败：" + exportDir.getAbsolutePath());
            }

            file = new File(exportDir, taskId + ".csv");

            long rowCount = generateCsv(dbSource, task.getSqlText(), file);

            task.setStatus(ExportStatus.SUCCESS);
            task.setFilePath(file.getPath());
            task.setRowCount(rowCount);
            task.setFinishedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("导出任务执行失败。taskId={}", taskId, e);

            // 失败时把写了一半的 CSV 删掉：留下一个内容不完整的文件、
            // 而数据库里又没有它的路径，磁盘上就会慢慢堆一批没人认领的垃圾
            if (file != null && file.exists() && !file.delete()) {
                log.warn("导出失败后清理半成品文件失败，需要人工处理。path={}", file.getAbsolutePath());
            }

            task.setStatus(ExportStatus.FAILED);
            // 只取消息不取堆栈：error_message 是要展示给用户看的字段，
            // 堆栈在日志里已经有了
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
