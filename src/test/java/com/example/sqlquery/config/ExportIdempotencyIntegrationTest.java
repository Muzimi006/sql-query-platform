package com.example.sqlquery.config;

import com.example.sqlquery.common.ExportStatus;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.dto.ExportTaskDTO;
import com.example.sqlquery.entity.ExportTask;
import com.example.sqlquery.mapper.ExportTaskMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.ExportService;
import com.example.sqlquery.vo.DbSourceVO;
import com.example.sqlquery.vo.ExportTaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出链路的<b>端到端</b>回归测试：真实 MySQL + 真实 Redis + 真实 RabbitMQ。
 *
 * <p>它守的是"重复投递"这个 MQ 的固有行为。
 * MQ 只承诺"至少一次"，Broker 没收到 ack 就会重投，
 * 所以同一个 taskId 被消费两次<b>不是异常，是常态</b>。
 * 用 mock 测这条路径没有说服力 —— 幂等靠的是一条条件 UPDATE 语句，
 * 只有在真实数据库上执行才会有"影响 0 行"这个结果。
 *
 * <p>判定依据不是"代码有没有走对分支"，而是<b>可观测的外部事实</b>：
 * 重复投递之后，CSV 文件的最后修改时间不变（说明没有被重写），
 * 任务状态仍然是 SUCCESS（没有被退回 RUNNING）。
 *
 * <p>运行前置条件：本地 MySQL(3306, root/123456) 、Redis(6379)、RabbitMQ(5672)，
 * 以及 DB_USERNAME / DB_PASSWORD / JWT_SECRET / AES_KEY 四个环境变量。
 * 运行方式：{@code mvn test -Dgroups=integration -Dsurefire.excludedGroups=}
 */
@Tag("integration")
@SpringBootTest
class ExportIdempotencyIntegrationTest {

    /** 与被测数据源无关的固定测试用户 id：data_source.user_id 没有外键约束，不需要真的建用户。 */
    private static final Long TEST_USER_ID = 990_001L;

    /** 重复投递后等待的总时长：确认"什么都没发生"需要一个观察窗口。 */
    private static final long OBSERVE_MS = 3000;

    @Autowired
    private ExportService exportService;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private ExportTaskMapper exportTaskMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final List<Long> createdSourceIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long taskId : createdTaskIds) {
            ExportTask task = exportTaskMapper.selectById(taskId);
            if (task != null && task.getFilePath() != null) {
                new File(task.getFilePath()).delete();
            }
            exportTaskMapper.deleteById(taskId);
        }
        createdTaskIds.clear();

        for (Long sourceId : createdSourceIds) {
            dbSourceService.removeById(sourceId);
        }
        createdSourceIds.clear();

        UserContext.clear();
    }

    @Test
    @DisplayName("同一任务被重复投递：不重跑、不覆盖文件、状态仍然是 SUCCESS")
    void duplicateDeliveryMustNotRerunTheExport() throws Exception {
        // 导出链路从 UserContext 取角色决定 SQL 白名单；测试线程里没有 Web 请求，需要自己设置
        UserContext.setRole("USER");

        Long sourceId = createRealDataSource();
        Long taskId = createExportTask(sourceId);

        ExportTask afterFirstRun = waitForFinished(taskId);
        assertEquals(ExportStatus.SUCCESS, afterFirstRun.getStatus(),
                "首次执行应该成功，实际：" + afterFirstRun.getErrorMessage());

        File csv = new File(afterFirstRun.getFilePath());
        assertTrue(csv.exists(), "导出文件应该落盘");
        long modifiedAfterFirstRun = csv.lastModified();

        // 模拟 Broker 重投：同一个 taskId 再发两次
        rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_QUEUE, taskId);
        rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_QUEUE, taskId);
        Thread.sleep(OBSERVE_MS);

        ExportTask afterRedelivery = exportTaskMapper.selectById(taskId);
        assertEquals(ExportStatus.SUCCESS, afterRedelivery.getStatus(),
                "重复投递把状态改了 —— 幂等保护失效");
        assertEquals(modifiedAfterFirstRun, csv.lastModified(),
                "CSV 文件被重写了 —— 重复投递真的又跑了一遍导出");
        assertEquals(afterFirstRun.getRowCount(), afterRedelivery.getRowCount(),
                "行数变了说明重新执行过");
    }

    private Long createRealDataSource() {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_EXPORT_SRC_" + System.nanoTime());
        dto.setType("MySQL");
        dto.setHost("127.0.0.1");
        dto.setPort(3306);
        dto.setDatabaseName("sql_query_platform");
        dto.setUsername(System.getenv().getOrDefault("DB_USERNAME", "root"));
        dto.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "123456"));

        dbSourceService.addDbSource(TEST_USER_ID, dto);

        DbSourceVO created = dbSourceService.listByUserId(TEST_USER_ID).stream()
                .filter(s -> s.getName().equals(dto.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚创建的数据源查不到"));
        createdSourceIds.add(created.getId());
        return created.getId();
    }

    private Long createExportTask(Long sourceId) {
        ExportTaskDTO dto = new ExportTaskDTO();
        dto.setDataSourceId(sourceId);
        dto.setSql("select id, username from user");

        ExportTaskVO task = exportService.createTask(TEST_USER_ID, dto);
        assertNotNull(task.getId(), "创建导出任务应该返回 id");
        createdTaskIds.add(task.getId());
        return task.getId();
    }

    /** 轮询直到任务离开 PENDING/RUNNING，或超时。 */
    private ExportTask waitForFinished(Long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        ExportTask task = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            task = exportTaskMapper.selectById(taskId);
            if (task != null && (ExportStatus.SUCCESS.equals(task.getStatus())
                    || ExportStatus.FAILED.equals(task.getStatus()))) {
                return task;
            }
        }
        throw new AssertionError("导出任务在 30 秒内没有结束，最后状态："
                + (task == null ? "任务查不到" : task.getStatus()));
    }
}
