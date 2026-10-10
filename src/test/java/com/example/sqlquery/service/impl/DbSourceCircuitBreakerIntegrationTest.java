package com.example.sqlquery.service.impl;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.dto.DbSourceDTO;
import com.example.sqlquery.dto.QueryDTO;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.QueryService;
import com.example.sqlquery.vo.DbSourceVO;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器的**阈值与行为实测**（第六轮，item 3 的第一件）。

 * <h2>为什么要专门实测它</h2>
 *
 * <p>在此之前，文档里对熔断阈值一直写的是「**阈值的取值没有实测依据**，
 * 是按 5 个连接 × 一次失败估的」。而"没实测"这句话本身有两种可能：
 * <b>只是没量过</b>，或者<b>它根本不会触发</b> —— 本项目在另一处（cause 被丢掉）
 * 已经吃过一次后者的亏，所以这一条必须量出来。

 * <h2>怎么量</h2>
 *
 * <p>用一个**协议规定不可路由**的地址 {@code 192.0.2.1}（TEST-NET-1）当数据源：
 * 建连必然超时（MySQL 驱动的 {@code connectTimeout=5000}），而且失败原因稳定。
 * 于是可以用**耗时**区分两种请求：

 * ```text
 * 真的去连了   → 要等满建连超时（约 5 秒）
 * 被熔断拒绝   → 不碰网络，毫秒级返回
 * ```

 * <p>这是这个用例的核心手法：**不看日志、不看状态字段，只看耗时数量级的差别**。
 * 它同时验证了三件事：阈值到了会打开、打开后真的不再尝试连接、冷却后确实放了一个探测请求进去。
 *
 * <h2>⚠️ 这个用例还顺带抓出了一个真 bug</h2>
 *
 * <p>写它的时候发现：数据源连不上时，连接池是**惰性创建**的，而 HikariCP 创建池失败抛的是
 * {@code PoolInitializationException}（RuntimeException）——
 * 它当年会穿过 {@code QueryServiceImpl} 的 {@code catch (BusinessException)}，
 * 于是<b>熔断器从来没被告知这个数据源连不上</b>，永远不打开。
 * 修法见 {@code QueryServiceImpl} 里的第二个 catch 分支；本用例第 2 条断言就是它的回归保护。
 */
@Tag("integration")
@SpringBootTest(properties = {
        // 把阈值压到 2、冷却压到 2 秒：真实阈值是 5 / 30 秒，
        // 而这个用例每次"真的去连"都要等满 5 秒建连超时，用默认值会慢到没法跑。
        "platform.query-load.circuit-breaker.failure-threshold=2",
        "platform.query-load.circuit-breaker.open-seconds=2"
})
class DbSourceCircuitBreakerIntegrationTest {

    /** 协议保留的测试网段，保证不可路由（见 RFC 5737）。 */
    private static final String UNREACHABLE_HOST = "192.0.2.1";

    private static final Long TEST_USER_ID = 990_004L;

    /** 被拒绝的请求必须**明显快于**一次真实建连尝试；5 秒 vs 这个数，量级差得很远。 */
    private static final long FAST_REJECTION_MS = 500;

    @Autowired
    private QueryService queryService;

    @Autowired
    private DbSourceService dbSourceService;

    @Autowired
    private MeterRegistry meterRegistry;

    private final List<Long> createdSourceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdSourceIds) {
            dbSourceService.removeById(id);
        }
        createdSourceIds.clear();
        UserContext.clear();
    }

    private Long createUnreachableDataSource() {
        DbSourceDTO dto = new DbSourceDTO();
        dto.setName("IT_CIRCUIT_" + System.nanoTime());
        dto.setType("MySQL");
        dto.setHost(UNREACHABLE_HOST);
        dto.setPort(3306);
        dto.setDatabaseName("whatever");
        dto.setUsername("nobody");
        dto.setPassword("nothing");
        dbSourceService.addDbSource(TEST_USER_ID, dto);

        DbSourceVO created = dbSourceService.listByUserId(TEST_USER_ID).stream()
                .filter(s -> s.getName().equals(dto.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚创建的数据源查不到"));
        createdSourceIds.add(created.getId());
        return created.getId();
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    /** 执行一次查询，返回耗时（毫秒）；不关心成功 —— 这个数据源不可能连上。 */
    private long attempt(Long dataSourceId, String sql) {
        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(dataSourceId);
        dto.setSql(sql);
        long start = System.currentTimeMillis();
        assertThrows(BusinessException.class, () -> queryService.execute(TEST_USER_ID, dto));
        return System.currentTimeMillis() - start;
    }

    @Test
    @DisplayName("⭐ 阈值到了会熔断，冷却后放一个探测请求，探测失败就继续熔断（毫秒级对比）")
    void thresholdOpensThenHalfOpenProbeThenReopens() {
        // 角色必须是白名单里允许 SELECT 的：否则会在"权限校验"这一步就被拦下，
        // 根本走不到连接数据库那一步（第一版用了 SUPER_ADMIN，18 ms 就返回了）
        UserContext.setRole("ADMIN");
        Long dataSourceId = createUnreachableDataSource();

        double openedBefore = counter(SqlPlatformMetrics.CIRCUIT_OPENED);
        double rejectedBefore = counter(SqlPlatformMetrics.CIRCUIT_REJECTED);

        // ① 阈值是 2：前两次请求会**真的去连**，每次都要等满建连超时
        long firstAttempt = attempt(dataSourceId, "SELECT 1");
        long secondAttempt = attempt(dataSourceId, "SELECT 1");
        System.out.printf("【实测】阈值前两次尝试耗时：%d ms / %d ms（应为建连超时量级）%n",
                firstAttempt, secondAttempt);
        assertTrue(firstAttempt >= 4000,
                "第一次应当真的去连并等满建连超时，实际只有 " + firstAttempt + " ms —— "
                        + "如果它很快返回，说明失败原因不是连接超时，这条用例的前提就不成立了");

        // ② 达到阈值 → 熔断打开
        assertEquals(openedBefore + 1, counter(SqlPlatformMetrics.CIRCUIT_OPENED),
                "连续失败达到阈值后，熔断器必须打开（这才是它存在的意义）");

        // ③ 打开之后：不碰网络，毫秒级拒绝 —— 这是"熔断省下了建连超时"的直接证据
        long rejectedAttempt = attempt(dataSourceId, "SELECT 1");
        System.out.printf("【实测】熔断打开后被拒绝的请求耗时：%d ms（对比建连超时的 %d ms）%n",
                rejectedAttempt, firstAttempt);
        assertTrue(rejectedAttempt < FAST_REJECTION_MS,
                "被熔断拒绝的请求不该去连数据库，实测耗时 " + rejectedAttempt + " ms");
        assertEquals(rejectedBefore + 1, counter(SqlPlatformMetrics.CIRCUIT_REJECTED),
                "被拒绝要有计数 —— 否则「在熔断」这件事不可观测");

        // ④ 冷却 2 秒后进入半开：**放一个请求真的去探测**（所以它又会慢下来）
        sleepQuietly(2_200);
        long probeAttempt = attempt(dataSourceId, "SELECT 1");
        System.out.printf("【实测】冷却后半开探测耗时：%d ms（应当重新变成建连超时量级）%n",
                probeAttempt);
        assertTrue(probeAttempt >= 4000,
                "半开状态必须真的放一个请求去试探，实测 " + probeAttempt + " ms");

        // ⑤ 探测失败 → 重新熔断，于是下一个请求又是毫秒级拒绝
        long rejectedAgain = attempt(dataSourceId, "SELECT 1");
        System.out.printf("【实测】探测失败后再次熔断，拒绝耗时：%d ms%n", rejectedAgain);
        assertTrue(rejectedAgain < FAST_REJECTION_MS,
                "探测失败后应当继续熔断，实测 " + rejectedAgain + " ms");
    }

    @Test
    @DisplayName("⭐ 数据源连不上时，给用户的必须是「连接失败」而不是「系统异常」（回归保护）")
    void unreachableDataSourceShouldGiveBusinessMessageNotSystemError() {
        // 角色必须是白名单里允许 SELECT 的：否则会在"权限校验"这一步就被拦下，
        // 根本走不到连接数据库那一步（第一版用了 SUPER_ADMIN，18 ms 就返回了）
        UserContext.setRole("ADMIN");
        Long dataSourceId = createUnreachableDataSource();

        QueryDTO dto = new QueryDTO();
        dto.setDataSourceId(dataSourceId);
        dto.setSql("SELECT 1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> queryService.execute(TEST_USER_ID, dto));

        System.out.printf("【实测】连不上数据源时抛出的异常：%s / %s%n",
                e.getClass().getSimpleName(), e.getMessage());
        assertTrue(e.getMessage().contains("连接失败") || e.getMessage().contains("不可用"),
                "连不上数据源不该是「系统异常」，实际文案：" + e.getMessage());
        // ⚠️ 这条断言是那个真 bug 的回归保护：
        //    连接池创建失败抛的是 Hikari 的 PoolInitializationException（RuntimeException），
        //    它曾经穿过 catch (BusinessException)，导致①熔断器永远不知道②用户看到"系统异常"。
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
