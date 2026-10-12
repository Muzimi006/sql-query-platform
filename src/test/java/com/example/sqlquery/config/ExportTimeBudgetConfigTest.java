package com.example.sqlquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出链路的「时间预算」不变式 —— 用**真实 application.yml** 校验，而不是靠启动日志。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code ExportTaskRecoveryJob#warnIfTimeBudgetTooSmall()} 会在启动时检查
 * {@code stuck-running-minutes * 60 > max-attempts * query-timeout-seconds}，
 * 但那条检查**只打 ERROR 日志**（刻意的：已经在跑的部署不该因为一个时间预算起不来）。
 *
 * <p>于是它有一个和「注释不会拦人」一模一样的盲区：<b>日志会被刷过去</b>。
 * 本项目真实发生过一次 —— 加了退避重试之后 {@code max-attempts} 变成 3，
 * 而 {@code stuck-running-minutes} 还是 10（默认值本来是 20）：
 * 一条接近超时的导出最坏要跑 3 × 300s = 15 分钟，
 * 补偿任务却在第 10 分钟就把它判成卡死 —— 而消费者还在第 2 次尝试。
 *
 * <p>所以这里把同一条件搬进测试：<b>启动日志漏看，测试不会漏看</b>。
 *
 * <h2>为什么还要断言「配置项必须在 yml 里显式写出」</h2>
 *
 * <p>这次错配的根因不是算错，而是**同一个值有两个来源**：
 * Java 侧 {@code @Value} 默认值是 20，yml 里却写着 10。
 * 只要有一边是隐式的，读配置的人就看不出真实生效值。
 */
class ExportTimeBudgetConfigTest {

    private static final String CONFIG = "/application.yml";

    private static Map<String, Object> yaml() {
        try (InputStream in = ExportTimeBudgetConfigTest.class.getResourceAsStream(CONFIG)) {
            assertNotNull(in, "找不到 " + CONFIG + "：测试依赖 src/main/resources 在测试类路径上");
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + CONFIG + " 失败", e);
        }
    }

    /** 按 a.b.c 取值；缺任何一层都返回 null（调用方负责断言）。 */
    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> root, String path) {
        Object cur = root;
        for (String key : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(key);
        }
        return cur;
    }

    private static int intOf(Map<String, Object> root, String path) {
        Object v = get(root, path);
        return v == null ? -1 : Integer.parseInt(String.valueOf(v).trim());
    }

    @Test
    @DisplayName("⭐ 补偿阈值必须大于「最坏执行耗时」：重试次数要算进去")
    void stuckThresholdMustCoverWorstCaseExecution() {
        Map<String, Object> root = yaml();
        int stuckMinutes = intOf(root, "export.stuck-running-minutes");
        int queryTimeoutSeconds = intOf(root, "export.query-timeout-seconds");
        int maxAttempts = intOf(root, "mq.consumer.retry.max-attempts");

        String numbers = "stuck-running-minutes=" + stuckMinutes + "（" + (stuckMinutes * 60) + "s）"
                + "、max-attempts=" + maxAttempts
                + "、query-timeout-seconds=" + queryTimeoutSeconds
                + " → 最坏 " + (maxAttempts * queryTimeoutSeconds) + "s";

        assertTrue(stuckMinutes > 0 && queryTimeoutSeconds > 0 && maxAttempts > 0,
                "三个值都必须能在 application.yml 里读到（缺一个就说明它靠的是 @Value 默认值）：" + numbers);

        int thresholdSeconds = stuckMinutes * 60;
        int worstCaseSeconds = maxAttempts * queryTimeoutSeconds;
        assertTrue(thresholdSeconds > worstCaseSeconds,
                "⚠️ 补偿阈值小于最坏执行耗时，正常任务会被误判为卡死：" + numbers);

        // 与启动检查给出的建议一致：至少留 5 分钟余量（排队 + 回写）。
        assertTrue(thresholdSeconds - worstCaseSeconds >= 300,
                "阈值与最坏耗时之间的余量不足 5 分钟（排队与回写也要时间）：" + numbers);
    }

    @Test
    @DisplayName("临时文件保留时长必须写在 yml 里：它决定 .tmp 由谁兜底清理")
    void tmpRetentionMustBeExplicit() {
        Map<String, Object> root = yaml();
        Object v = get(root, "export.tmp-retention-hours");
        assertNotNull(v, "export.tmp-retention-hours 必须显式写在 application.yml 里 ——"
                + "它决定「进程被 kill -9 留下的 .tmp 多久后被清理任务扫掉」，"
                + "写死在 @Value 默认值里的话，读配置的人看不到它");
        assertTrue(Integer.parseInt(String.valueOf(v).trim()) > 0, "必须是正数小时");
    }
}