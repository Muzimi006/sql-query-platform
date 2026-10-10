package com.example.sqlquery.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.util.ConnectionManager;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
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
 * <h2>五个必须做对的地方</h2>
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
 *
 * <p><b>四、导出的 SQL 也必须有超时和行数上限。</b>
 * 这两样查询链路一直有（{@code setQueryTimeout(30)} + {@code setMaxRows(1001)}），
 * 但导出链路最初<b>漏了</b> —— 而它用的是<b>同一个连接池</b>（上限 5），
 * 一条挂住的慢 SQL 或者一次全表扫描，影响的不只是这一个导出任务。
 * 见 {@link #generateCsv}。
 *
 * <p><b>五、瞬时故障要自己扛过去，扛不过去才交给人。</b>
 * 原来"写不回去"就一次进死信队列 —— 数据库抖两秒就得人工重放。
 * 现在退避重试 {@link #maxAttempts} 次。
 * ⚠️ 这条<b>只对导出成立</b>：导出只允许 SELECT，重跑无副作用；
 * 工单链路执行的是 UPDATE，重跑 = 再改一次数据，所以它刻意不重试。
 * 见 {@link #handle}。
 */
@Slf4j
@Component
public class ExportConsumer {

    /** UTF-8 BOM，写在 CSV 文件最开头，让 Excel 正确识别编码。 */
    private static final char UTF8_BOM = '\uFEFF';

    /**
     * 单次导出最多写多少行。
     *
     * <p>比查询链路的 {@code MAX_ROWS}（1000）宽得多 —— 导出本来就是为了拿大批数据。
     * 但<b>不能没有上限</b>：一条 {@code select * from 大表} 会把磁盘写满，
     * 而磁盘写满影响的是<b>整台机器上的所有服务</b>，不只是这一个导出。
     */
    @Value("${export.max-rows:100000}")
    private int maxRows;

    /**
     * 单条导出 SQL 的执行超时（秒）。
     *
     * <p>比查询链路的 30 秒宽，因为导出还要往磁盘写。
     * 但<b>必须有</b>：导出连接来自<b>同一个连接池</b>（上限 5），
     * 一条挂住的慢 SQL 会把池子慢慢占满，而池子是所有查询和导出共用的。
     *
     * <p>⚠️ 这个值必须<b>明显小于</b> {@code export.stuck-running-minutes}
     * （卡单补偿的阈值），否则补偿任务会把一个仍在正常执行的导出判成卡死。
     */
    @Value("${export.query-timeout-seconds:300}")
    private int queryTimeoutSeconds;

    /**
     * 一次投递最多尝试几次（含第一次）。
     *
     * <h2>重试的是「基础设施故障」，不是「业务失败」</h2>
     *
     * <p>业务失败（SQL 报错、超出行数上限）在 {@link #execute} 内部就被处理掉了 ——
     * 它把任务写成 FAILED 然后正常返回，**根本不会走到重试**。
     * 能走到这里的只有一种情况：<b>连「把结果写回数据库」都做不到</b>
     * （数据库暂时不可用、连接被掐断）。这类失败是<b>瞬时的</b>，
     * 过一两秒再试一次往往就好了。
     *
     * <p>而原来的处理是<b>一次失败直接进死信队列</b> ——
     * 数据库抖了两秒，一批导出任务就被打成「需要人工重放」。
     * 代价完全不对等：人工介入的成本远高于多试两次。
     *
     * <h2>为什么这条链路可以重试，而工单链路不行</h2>
     *
     * <p>因为<b>导出只允许 SELECT</b>（提交时 {@code SqlValidateUtil} 已按
     * {@code Set.of(SqlType.SELECT)} 校验过），重跑一次是<b>只读</b>的、无副作用。
     *
     * <p>工单链路执行的是 {@code UPDATE}，重跑就是<b>再改一次数据</b> ——
     * 那正是项目里那条铁律「<b>写语句绝不自动重试</b>」要禁止的事。
     * 所以 {@link SqlReviewConsumer} <b>刻意没有</b>这个循环。
     * 两条链路在这里长得不一样，是有意的，不是漏了。
     */
    @Value("${mq.consumer.retry.max-attempts:3}")
    private int maxAttempts = 3;

    /**
     * 两次尝试之间的等待（毫秒），第 n 次失败后等 {@code n × 这个值}。
     *
     * <p>退避而不是立刻重试：如果数据库是真的挂了，立刻重试只会
     * 用最快的速度把重试次数烧完（三次重试总共几毫秒），等于没重试。
     *
     * <p>默认 1 秒线性退避 → 3 次尝试覆盖约 <b>3 秒</b>的故障窗口。
     * 这个窗口<b>故意不长</b>：等待期间消费者线程是被按住的
     * （{@code prefetch: 1} 且单消费者，等于这个队列停摆）。
     * 要把窗口拉长，正确做法是加一条带 TTL 的延迟重试队列
     * 把等待搬到 Broker 侧，而不是让消费者在这里睡更久。
     */
    @Value("${mq.consumer.retry.backoff-ms:1000}")
    private int retryBackoffMs = 1000;

    private final ExportTaskMapper exportTaskMapper;
    private final DbSourceService dbSourceService;
    private final ConnectionManager connectionManager;
    private final SqlPlatformMetrics metrics;

    public ExportConsumer(ExportTaskMapper exportTaskMapper, DbSourceService dbSourceService,
                          ConnectionManager connectionManager, SqlPlatformMetrics metrics) {
        this.exportTaskMapper = exportTaskMapper;
        this.dbSourceService = dbSourceService;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
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
     *   <li>连"把结果写回数据库"都做不到（数据库不可用等）→ <b>先退避重试</b>
     *       （见 {@link #maxAttempts}），<b>重试次数用尽才 nack 且不重新入队</b>
     *       → 消息进死信队列。</li>
     * </ul>
     *
     * <p>⚠️ 这里以前是"一次失败直接进死信"，理由写的是"就地重试只会空转"。
     * 那句话对<b>持续性</b>故障成立，但把所有"数据库抖了两秒"也一并当成了持续性故障 ——
     * 结果是一批本来自己就能好的任务被推给人工重放。现在把它拆成两件事：
     * <b>短窗口内的瞬时故障自己扛过去，扛不过去再交给人</b>。
     *
     * <p>为什么需要 {@link #sleepBeforeRetry} 里的退避而不是立刻重试：立刻重试会把
     * 三次机会在几毫秒内烧完，等于没重试。
     */
    @RabbitListener(queues = RabbitConfig.EXPORT_QUEUE, ackMode = "MANUAL")
    public void handle(Long taskId, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 认领是否已经成功。它决定重试时该做什么：
        //   false → 重试的是「认领」这一步（它本身是幂等的条件更新，重试安全）；
        //   true  → 重试的是「执行 + 回写」（导出只允许 SELECT，重跑无副作用）。
        // ⚠️ 分这两段是必须的。如果无脑把整个 try 块重来一遍，
        //    认领已经成功的情况下第二次 tryClaim 会返回 false，
        //    于是走进「已被认领 → ack」的分支，
        //    **把一条结果根本没写回的消息 ack 掉了** —— 任务永远停在 RUNNING。
        boolean claimed = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!claimed) {
                    if (!tryClaim(taskId)) {
                        log.info("导出任务已被认领或已完成，跳过本次投递（幂等保护）。taskId={}", taskId);
                        channel.basicAck(deliveryTag, false);
                        return;
                    }
                    claimed = true;
                }

                execute(taskId);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    log.error("导出任务尝试 {} 次仍未能落库，投递到死信队列。taskId={}, dlq={}",
                            maxAttempts, taskId, RabbitConfig.EXPORT_DLQ, e);
                    // requeue=false：进死信交换机。
                    // 用 true 重投的话，如果是持续性故障（数据库连不上），
                    // 消息会在队列里无限打转，把 CPU 和日志一起打满。
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                metrics.recordConsumerRetry(RabbitConfig.EXPORT_QUEUE);
                log.warn("导出任务第 {}/{} 次尝试失败，{} ms 后重试。taskId={}, cause={}",
                        attempt, maxAttempts, (long) retryBackoffMs * attempt, taskId, e.toString());

                if (!sleepBeforeRetry(attempt)) {
                    // 线程被中断（应用正在关闭）：不吞掉中断，也不假装还能继续。
                    // 把消息推给死信队列，让人看得见；任务状态由补偿任务收尾。
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断，投递到死信队列。taskId={}, dlq={}",
                            taskId, RabbitConfig.EXPORT_DLQ);
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }
            }
        }
    }

    /**
     * 退避等待。返回 {@code false} 表示等待期间线程被中断。
     *
     * <p>包级可见是为了让单元测试能把它替换掉 —— 否则每条重试用例都要真等好几秒。
     * 见 {@link ExportConsumerIdempotencyTest}。
     */
    boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep((long) retryBackoffMs * attempt);
            return true;
        } catch (InterruptedException e) {
            return false;
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
                .set(ExportTask::getStatus, ExportStatus.RUNNING)
                // ⭐ 认领的这一刻要记下来：卡死判定看的是"认领之后过了多久"，
                //    而不是"创建之后过了多久" —— 后者会把排队时间也算进去，
                //    队列积压时正常任务会被误判成卡死。见 ExportTaskRecoveryJob。
                .set(ExportTask::getStartedAt, LocalDateTime.now()));
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

            finish(taskId, ExportStatus.SUCCESS, file.getPath(), rowCount, null);
        } catch (Exception e) {
            log.error("导出任务执行失败。taskId={}", taskId, e);

            // 失败时把写了一半的 CSV 删掉：留下一个内容不完整的文件、
            // 而数据库里又没有它的路径，磁盘上就会慢慢堆一批没人认领的垃圾
            if (file != null && file.exists() && !file.delete()) {
                log.warn("导出失败后清理半成品文件失败，需要人工处理。path={}", file.getAbsolutePath());
            }

            // 只取消息不取堆栈：error_message 是要展示给用户看的字段，堆栈在日志里已经有了
            finish(taskId, ExportStatus.FAILED, null, null, e.getMessage());
        }
    }

    /**
     * 把执行结果写回任务。**必须是条件更新**，不能是 {@code updateById}。
     *
     * <h2>为什么</h2>
     *
     * <p>认领用的是条件更新（{@code PENDING → RUNNING}），但最早的回写是
     * {@code updateById} —— 按主键<b>整行覆盖</b>，{@code WHERE} 里没有那个认领令牌。
     * 于是出现一条很隐蔽的竞态：
     *
     * <ol>
     *   <li>消费者认领了任务，正在跑那条慢查询；</li>
     *   <li>{@link ExportTaskRecoveryJob} 认为它卡死了（阈值配置不当、或者队列积压导致
     *       排队时间也算进了 {@code created_at} 判定），把它标成 FAILED
     *       并写下"已由补偿任务标记为失败"；</li>
     *   <li>消费者跑完，{@code updateById} 把状态盖回 SUCCESS ——
     *       <b>而那句失败原因还留在 {@code error_message} 里</b>
     *       （MyBatis-Plus 默认的 NOT_NULL 策略下，把字段设成 null 根本不会进 SET 子句）。</li>
     * </ol>
     *
     * <p>最终用户看到"导出成功"，点进去却有一句"已标记为失败"。
     *
     * <p>工单链路后来也是同样的写法、同样的 bug，在那边被对抗式评审抓出来了
     * （见落地方案文档 §3.3.8）。这里是同一处修复。
     *
     * <p>⚠️ 用 {@code .set(...)} 显式写列，这样 {@code null} 也会进 SET 子句 ——
     * 成功时要清掉上次留下的错误信息，靠的正是这个。
     */
    private void finish(Long taskId, String status, String filePath, Long rowCount, String errorMessage) {
        int affected = exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                .eq(ExportTask::getId, taskId)
                // 认领令牌：只有"仍然是我认领的那条 RUNNING"才允许写回
                .eq(ExportTask::getStatus, ExportStatus.RUNNING)
                .set(ExportTask::getStatus, status)
                .set(ExportTask::getFilePath, filePath)
                .set(ExportTask::getRowCount, rowCount)
                .set(ExportTask::getErrorMessage, errorMessage)
                .set(ExportTask::getFinishedAt, LocalDateTime.now()));
        // 注：ExportTask 实体没有 updatedAt 字段（表里有，但实体没映射），
        // 而导出的卡死判定用的是 started_at（认领时刻，见 ExportTaskRecoveryJob），
        // 和工单链路（那个必须显式写，因为判定用的是 updated_at）不同。

        if (affected == 0) {
            log.error("导出任务已不再处于 RUNNING，本次结果未落库，需人工核对。"
                    + "taskId={}, 本次结果={}", taskId, status);
        }
    }

    /**
     * 把查询结果写成 CSV。
     *
     * <p>包级可见（而非 private）是为了让单元测试能直接调用它。
     *
     * <p><b>必须显式处理的四件事</b>：
     * <ol>
     *   <li><b>必须显式指定 UTF-8</b>：{@code FileWriter} 用的是平台默认字符集 ——
     *       Windows 上是 GBK、Linux 上是 UTF-8，同一份代码在不同平台产出的文件不一样，
     *       而且本机测不出来；</li>
     *   <li><b>必须在开头写 UTF-8 BOM</b>：Excel 不会自动识别「没有 BOM 的 UTF-8」，
     *       不写 BOM 时中文照样乱码。BOM 只在文件最开头写一次。</li>
     *   <li><b>必须有查询超时</b>：见 {@link #queryTimeoutSeconds}；</li>
     *   <li><b>必须有行数上限</b>：见 {@link #maxRows}。</li>
     * </ol>
     *
     * <p>⚠️ 后两条是<b>补上来的</b>。查询链路从一开始就有超时和行数上限，
     * 导出链路最初两个都没有 —— 而它用的是同一个连接池。
     * <b>"另一条链路已经做了"不等于"这条链路也做了"</b>，
     * 这是本项目里反复出现的一类疏漏（导出曾经也漏过 SQL 审核）。
     */
    long generateCsv(DbSource dbSource, String sql, File file) throws Exception {
        long rowCount = 0;

        try (Connection connection = connectionManager.getConnection(dbSource);
             // ⚠️ Statement 单独一层 try：必须在 executeQuery <b>之前</b>把它配置好。
             // 写成一个 try-with-resources 里连着 executeQuery 的话，
             // setQueryTimeout / setMaxRows 根本没有位置放。
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(queryTimeoutSeconds);
            // 多读一行，用来判断"是不是超了" —— 和查询链路判断截断是同一个技巧
            statement.setMaxRows(maxRows + 1);

            try (ResultSet resultSet = statement.executeQuery(sql);
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
                    rowCount++;
                    if (rowCount > maxRows) {
                        // ⚠️ 超限让<b>整个任务失败</b>，而不是截断后返回成功。
                        //
                        // 截断的 CSV 看起来是"完整的" —— 用户会以为数据齐了、拿去用；
                        // 而报错至少让他知道要缩小范围。
                        // **静默的假数据比一句报错危险得多**，这一点和查询链路不同
                        // （查询链路会截断并带 truncated 标记，因为结果就在屏幕上、看得见少不少）。
                        throw new BusinessException("导出结果超过 " + maxRows
                                + " 行上限，请缩小查询范围（加 where 条件或 limit）后重试");
                    }

                    for (int i = 1; i <= columnCount; i++) {
                        if (i > 1) {
                            writer.write(",");
                        }
                        Object value = resultSet.getObject(i);
                        writer.write(csvEscape(value == null ? "" : value.toString()));
                    }
                    writer.write("\n");
                }
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
