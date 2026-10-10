package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.vo.DbSourceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合并写：一次查询请求在主库上只提交 **1 次**（此前是 2 次）。
 *
 * <h2>为什么要有这条用例</h2>
 *
 * <p>档案 G-08 里算过这笔账：本项目跑的是 MySQL 双1，每次提交都要 fsync redo + binlog
 * （两阶段提交）= 2 次 fsync；而一次查询请求要写两张平台表（audit_log + query_history），
 * 各自提交就是 2 次提交 / 4 次 fsync。
 *
 * <p>实测过方向：双1 + 逐行提交 175 行/秒，双1 + 单事务 7,053 行/秒 ——
 * 想快，正解是减少提交次数，而不是牺牲耐久性。
 *
 * <h2>它量的是什么</h2>
 *
 * <p>MySQL 的 Com_commit 全局计数器：每执行一次 COMMIT 就 +1。
 * 用例的做法是「读一次 → 打一个真实查询请求 → 再读一次」，差值就是这次请求在主库上的提交次数。
 *
 * <p>⚠️ 查询必须走**真实 HTTP**（webEnvironment = RANDOM_PORT）：合并写的开启点是
 * 审计切面的 @Around，只有在一个请求里才会生效。直接调 Service 层测不到它 ——
 * 那走的正是「没人在收集时直接写库」那条路径（单测/定时任务/MQ 消费者）。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformWriteMergeIntegrationTest {

    /** 登录会话：后面要用**这个用户**的身份去建数据源与查询。 */
    private record Session(String token, Long userId) {
    }

    @LocalServerPort
    private int port;

    /**
     * 用 JDK 自带的 HttpClient 而不是 TestRestTemplate：
     * Spring Boot 4 里 TestRestTemplate 的包位置变了（org.springframework.boot.test.web.client 已不存在），
     * 而这里只需要发几个 JSON 请求 —— 用 JDK 自带的反而更少依赖、更少踩版本坑。
     */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private DataSource platformDataSource;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final List<Long> createdSourceIds = new ArrayList<>();

    /** 合并提交发生了多少次（应用自己记的，不受其它线程干扰）。 */
    private long flushCounter() {
        var counter = meterRegistry.find(SqlPlatformMetrics.WRITE_FLUSH).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    /** 被合并提交、且写成功的条数。 */
    private long flushedItemsTotal() {
        var counter = meterRegistry.find(SqlPlatformMetrics.WRITE_FLUSHED_ITEMS)
                .tag("outcome", "success").counter();
        return counter == null ? 0 : (long) counter.count();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdSourceIds) {
            dbSourceService.removeById(id);
        }
        createdSourceIds.clear();
        UserContext.clear();
    }

    /** 读一个 MySQL 全局状态变量（Com_commit = 累计提交次数）。 */
    private long globalStatus(String name) throws Exception {
        try (Connection connection = platformDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW GLOBAL STATUS LIKE '" + name + "'")) {
            assertTrue(rs.next(), "读不到状态变量 " + name);
            return Long.parseLong(rs.getString(2));
        }
    }

    /**
     * 建数据源。⚠️ 必须**建在登录用户名下**：HTTP 请求的身份是刚注册的那个用户，
     * 而数据源是按 userId 做归属校验的（第一版写成了固定测试用户，查询回了一句「数据源不存在」）。
     */
    private Long createDataSource(Long userId) {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_MERGE_" + System.nanoTime());
        dto.setType("MySQL");
        dto.setHost("127.0.0.1");
        dto.setPort(3306);
        dto.setDatabaseName("sql_query_platform");
        dto.setUsername(System.getenv().getOrDefault("DB_USERNAME", "root"));
        dto.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "123456"));
        dbSourceService.addDbSource(userId, dto);

        DbSourceVO created = dbSourceService.listByUserId(userId).stream()
                .filter(s -> s.getName().equals(dto.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚创建的数据源查不到"));
        createdSourceIds.add(created.getId());
        return created.getId();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 注册 + 登录拿 token（走真实 HTTP，因为鉴权拦截器只认真实请求）。 */
    @SuppressWarnings("unchecked")
    private Session login() throws Exception {
        String username = "mergeprobe_" + System.nanoTime();
        String password = "Probe12345!";
        post("/api/auth/register",
                Map.of("username", username, "password", password, "nickname", "mergeprobe"), null);
        HttpResponse<String> response = post("/api/auth/login",
                Map.of("username", username, "password", password), null);
        Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        return new Session(String.valueOf(data.get("token")),
                Long.valueOf(String.valueOf(user.get("id"))));
    }

    @Test
    @DisplayName("⭐ 一次查询请求在主库上只提交 1 次（合并前是 2 次：审计 + 历史各一次）")
    void oneQueryRequestShouldCommitOnce() throws Exception {
        Session session = login();
        Long dataSourceId = createDataSource(session.userId());

        // ⚠️ 用 Handler_commit 而不是 Com_commit：
        //    Com_commit 只统计**客户端显式发的 COMMIT 语句**，而自动提交下的单条 INSERT
        //    是**隐式提交**、不计入它 —— 于是「没合并」和「合并了」两种情况在它眼里都是 1，
        //    这条用例就白写了。第一版就是这么写的，反向验证时抓到（把历史写改成直接提交，
        //    Com_commit 仍然是 +1）。Handler_commit 统计的是**引擎内部的提交次数**（含隐式）。
        long commitsBefore = globalStatus("Handler_commit");
        long explicitBefore = globalStatus("Com_commit");
        long flushBefore = flushCounter();
        long flushedItemsBefore = flushedItemsTotal();

        // 只量这一个请求：它同时写审计与查询历史
        HttpResponse<String> response = post("/api/query/execute",
                Map.of("dataSourceId", dataSourceId, "sql", "SELECT 1"), session.token());
        assertTrue(response.body().contains("\"code\":200"),
                "这次查询请求本身要成功，否则量出来的提交次数没有意义。响应：" + response.body());

        long commits = globalStatus("Handler_commit") - commitsBefore;
        long explicit = globalStatus("Com_commit") - explicitBefore;
        long flush = flushCounter() - flushBefore;
        System.out.printf(
                "【实测】一次查询请求：合并提交 flush +%d，被合并的写 +%d 条（Handler_commit +%d / Com_commit +%d）%n",
                flush, flushedItemsTotal() - flushedItemsBefore, commits, explicit);
        assertEquals(1, flush,
                "一次查询请求只应当发生一次合并提交（审计 + 历史在同一个事务里）。"
                        + "如果这里是 0，说明缓冲没生效（两条写各自提交了）");
        assertEquals(2, flushedItemsTotal() - flushedItemsBefore,
                "这一次合并提交里应当带着两条写：审计 + 查询历史");

        // ⚠️ 这里**不断言** MySQL 的提交计数器（理由见上面那段注释：一个不统计隐式提交、
        //    一个是全局计数会被别的线程干扰）—— 它们只作为观测值打印出来。
        //    真正断言的是应用自己记录的「合并了几次、这次带上了几条写」。
    }
}
