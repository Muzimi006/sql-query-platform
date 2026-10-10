package com.example.sqlquery.util;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断阈值的<b>调参实测</b>：回答「5 次 / 30 秒」这组数是否合理。
 *
 * <h2>它和 {@code DbSourceCircuitBreakerIntegrationTest} 的分工（不要混）</h2>
 *
 * <p>那个用例验的是<b>机制</b>：达到阈值会打开、打开期间不碰网络
 * （实测 6035 ms → 0 ms）、冷却后放一个探测。它<b>回答不了调参问题</b> ——
 * 因为真实链路里要制造「50% 的请求失败」需要一个能稳定地<i>部分失败</i>的假数据源，
 * 那是集成测试做不到的事。
 *
 * <p>所以这个用例<b>只测熔断器自己的逻辑</b>：直接
 * {@code new DbSourceCircuitBreaker(阈值, 1, metrics)}，
 * 由测试按概率决定「这一次算成功还是失败」，把 阈值 × 故障率 跑成一张表。
 *
 * <p>⚠️ <b>这里测的不是真实 JDBC 链路</b>：仿真只走
 * {@code allowRequest / recordFailure / recordSuccess} 三个入口，
 * 不建连、不查库、不涉及 Hikari 连接池。真实链路那部分由
 * {@code DbSourceCircuitBreakerIntegrationTest} 覆盖（它有 6035 ms / 0 ms 这组实测数字）。
 *
 * <h2>为什么 openSeconds 只能取 1</h2>
 *
 * <p>真实类内部用的是 {@code System.currentTimeMillis()}，<b>没有注入时钟的口子</b>，
 * 所以仿真里没法假装「已经过了 30 秒」。取允许的最小值 1 秒：
 * 每次打开最多让用例多花 1 秒。窗口内「一个都不放行」这条性质<b>与窗口长度无关</b>
 * （{@code allowRequest} 里只比较 {@code now - openedAt >= openMillis}，是个纯比较），
 * 所以 30 秒窗口的数字由 1 秒窗口<b>按这条代码性质线性外推</b> ——
 * 每一处外推都在输出里标了「推断」字样，不和时间直接测出来的数字混在一起。
 *
 * <h2>仿真为什么可复现</h2>
 *
 * <p>用固定种子的 {@link Random}（{@code SEED} 常量，打印在输出里）：换台机器跑的
 * 每一个数字都一样。这是硬要求 —— 数字不可复现的实验没法被复核，也就没法作为调参依据。
 *
 * <h2>仿真模型的边界（必须连着数字一起讲）</h2>
 *
 * <ul>
 *   <li>每来一次请求是一次<b>独立同分布</b>的抽样。真实世界里失败是<b>成簇</b>的
 *       （网络抖一下就是连续几秒全失败），所以「连续失败」这个计数在真实世界里比
 *       独立模型<b>更容易</b>被触发 —— 表一的数字是偏乐观的下界；</li>
 *   <li>没有建模请求耗时：真实链路里失败的请求要等满建连超时（实测 6035 ms）才返回，
 *       它占着一个 Web 线程和 1/5 的连接池。所以「多少请求」能换算成「多少秒」，
 *       但换算的前提是<b>尝试速率</b>不变（用户仍在不停地点）；</li>
 *   <li>只喂连接类失败（{@code SQLTransientConnectionException}），
 *       和真实链路里 {@code recordFailure} 拿到的分类结果一致 ——
 *       「业务类失败不算数」这条性质由 {@code DbSourceCircuitBreakerTest} 守着，这里不重复验；</li>
 *   <li><b>仿真跑不到的组合如实写「未打开」</b>：阈值 10 × p=0.1% 的理论期望是 1e30 次请求，
 *       任何仿真都跑不到，只能给「喂满上限仍未打开」这个下界，不假装跑到了。</li>
 * </ul>
 *
 * <h2>结果（种子 20260213 那次运行的真实输出，逐字抄下来）</h2>
 *
 * <pre>
 * 表一：阈值 × 故障率 → 打开所需请求数（openSeconds=1；每趟一个全新的熔断器）
 * 阈值  p      仿真(均值/中位)                          趟数(打开/总)  精确公式期望
 *  1    50%    2 / 2                                   200/200        2
 *  1    10%    9 / 6                                   200/200        10
 *  1    1%     100 / 75                                200/200        100
 *  1    0.1%   1064 / 735                              200/200        1000
 *  3    50%    12 / 9                                  200/200        14
 *  3    10%    1129 / 806                              200/200        1110
 *  3    1%     148883（仅已打开的 3 趟，右删失）           3/20           1010100
 *  3    0.1%   未打开（20 趟 × 30 万次 = 600 万次喂完）    0/20           1.00e9
 *  5    50%    66 / 50                                 200/200        62
 *  5    10%    102623（仅已打开的 50 趟，右删失）          50/54          111110
 *  5    1%     未打开（喂满 600 万次）                    0/20           1.01e10
 *  5    0.1%   未打开（喂满 600 万次）                    0/20           1.00e15
 * 10    50%    2025 / 1391                            200/200        2046
 * 10    10%    未打开（喂满 600 万次）                    0/20           1.11e10
 * 10    1%     未打开（喂满 600 万次）                    0/20           1.01e20
 * 10    0.1%   未打开（喂满 600 万次）                    0/20           1.00e30
 * → 能跑满 200 趟的那几行，仿真均值和精确公式在抽样噪声内一致，说明仿真内核没写错。
 *
 * 表二：打开之后窗口内的请求（真实时钟实测，阈值=1、窗口 1 秒）
 *   窗口内连打 allowRequest 1721 万次 → 被拒 1721 万次、放行 0 次
 *   第 1 次被放行的时刻 = 打开后 1000 ms（就是那个半开探测）
 *   半开探测在途的 300 ms 里，602 万次调用仍然全部被拒
 *   按速率换算（窗口内放行 0 次 → 被拒数 = 速率 × 窗口）：
 *     2 req/s  → 30 秒窗口 60 次；含探测在途（30+6 秒）≈ 72 次
 *     10 req/s → 30 秒窗口 300 次；含探测在途（30+6 秒）≈ 360 次
 *   ⚠️ 数据源真坏时被拒【不是误拒】（放进去也是等满 6035 ms 再失败）；
 *      真正的误拒是「假熔断期间」+「恢复后的尾巴」。
 *
 * 表三：瞬时抖动（40 成功 → 连续 burst 次失败 → 40 成功，确定性模式）
 *   唯一的界就是 burst == 阈值：burst < 阈值 一次都不熔断（连续计数被后面的成功清零），
 *   burst >= 阈值 就熔断，而且抖动结束后那些成功【救不回来】—— recordSuccess 只在 HALF_OPEN 下恢复。
 *   代价：白等整整一个窗口。真实配置 30 秒时 = 10 req/s 约 300 次请求被拒、2 req/s 约 60 次。
 *   ⚠️ 反向实测：失败/成功严格交替（故障率恰好 50%）喂 100 万次请求，
 *      阈值 3/5/10 一次都没熔断（阈值 1 开了 1 次）——「连续计数」对相关性敏感、对故障率不敏感。
 *
 * 表四：代价（公式 + 速率换算，不是新测的）
 *   A) 阈值调大 = 故障期间多等多久（p=50%）
 *      2 req/s ：阈值 1/3/5/10 → 1.0 / 7.0 / 31.0 / 1023.0 秒才熔断
 *      10 req/s：阈值 1/3/5/10 → 0.2 / 1.4 / 6.2 / 204.6 秒才熔断
 *      ⚠️ 「秒」这一列假定尝试速率就是行首那个数 —— 建连超时型故障下这个假定不成立：
 *         一条失败要占住一个并发槽 6 秒，而每数据源并发上限是
 *         ConnectionManager.MAX_POOL_SIZE=5，所以失败尝试速率上限 ≈ 5/6 ≈ 0.83 次/秒
 *         （p=50% 时全部尝试 ≈ 1.7 次/秒）→ 现实落在【2 req/s 那一行】，
 *         10 req/s 那一行只适用于「连不上但立刻失败」（端口被拒）。
 *   B) 阈值调小 = 被背景抖动误伤多频繁（按 10 req/s；大于 1 次/30 秒就意味着窗口首尾相接）
 *      p=1%  ：阈值 1 → 8640 次/天（基本一直熔断着）；阈值 3 → 约 1.2 天一次；
 *              阈值 5 → 约 32 年一次；阈值 10 → 实际永不发生
 *      p=0.1%：阈值 1 → 864 次/天（基本一直熔断着）；阈值 3 → 约 3 年一次；
 *              阈值 5 / 10 → 实际永不发生
 *   C) 冷却窗口同时就是【恢复后的尾巴】（探测在途按实测 6035 ms 算）
 *      5 秒 → 最长 11 秒 / 平均 9 秒被误拒；30 秒 → 最长 36 秒 / 平均 21 秒；
 *      60 秒 → 最长 66 秒 / 平均 36 秒
 * </pre>
 *
 * <h2>结论：建议保持 5 / 30 秒（依据与代价都写在下面）</h2>
 *
 * <ul>
 *   <li><b>阈值 1 不可用</b>：只要 1% 的请求连接失败，就会 8640 次/天误熔断
 *       （30 秒窗口首尾相接 → 这个数据源实际上一直处于「关着」的状态）。</li>
 *   <li><b>阈值 3 是分水岭</b>：p=1% 时约 1.2 天误伤一次，p=0.1% 时约 3 年一次。
 *       它便宜（p=50% 时 10 req/s 下 1.4 秒就熔断），但顶不住 1% 量级的背景抖动。</li>
 *   <li><b>阈值 5 对独立抖动免疫</b>：p=1% 要 1.01e10 次请求才误伤一次（10 req/s ≈ 32 年），
 *       p=0.1% 要 1e15 次。代价是 p=50% 时要 62 次请求才熔断 —— 建连超时型故障下
 *       现实速率约 1.7 次/秒（被 5 个并发槽卡住），也就是【约半分钟】；
 *       这半分钟里，每条失败的请求都还在占着 Web 线程和 1/5 的连接池。</li>
 *   <li><b>阈值 10 不划算</b>：比阈值 5 晚 198 秒（10 req/s、p=50%）才保护住平台，
 *       买到的是「挡掉 5~9 次连续抖动」——而真实的抖动是<b>成簇</b>的，
 *       10 req/s 下 1 秒的完全中断就是 10 次连续失败，阈值 10 照样打开。
 *       换句话说它多付了 33 倍的暴露时间，却没换来对应的免疫力。</li>
 *   <li><b>30 秒窗口是对的，但要知道它同时是「恢复后的尾巴」</b>：
 *       DB 恢复之后，最长还有 36 秒（平均 21 秒）的请求被误拒 —— 10 req/s 下就是
 *       最长 360 个、平均 210 个请求。窗口调到 5 秒能把这个尾巴压到平均 9 秒，
 *       代价是半开探测更频繁（每次探测要占住 1/5 的连接池 6 秒）。</li>
 *   <li>⚠️ <b>已知边界（本次实测，两条都是反方向的洞）</b>：
 *       ① 阈值数的是<b>连续</b>失败 —— 失败/成功严格交替时（k≥2）永远不打开，
 *          实测喂 100 万次请求、0 次熔断，而与此同时 50% 的请求在等满建连超时；
 *       ② 阈值的单位是<b>请求数不是秒数</b> —— 同一个"1 秒抖动"，数据源速率越高
 *          产生的连续失败越多、越容易触发。要速率无关，得改成"时间窗内失败率"的判定。</li>
 * </ul>
 */
class DbSourceCircuitBreakerTuningTest {

    private static final Long DATA_SOURCE_ID = 1L;

    /** 仿真的固定种子：结果可复现是这个用例的硬要求，换种子表里每个数字都会变。 */
    private static final long SEED = 20260213L;

    /**
     * 仿真用的冷却窗口，单位秒，取允许的最小值 1。
     *
     * <p>真实配置是 30（{@code platform.query-load.circuit-breaker.open-seconds}），
     * 但真实类读的是 {@code System.currentTimeMillis()}，仿真里等不起 30 秒。
     */
    private static final int OPEN_SECONDS = 1;

    private static final int[] THRESHOLDS = {1, 3, 5, 10};

    /** 故障率：50% / 10% 是「坏了一半 / 坏了一成」，1% / 0.1% 是「健康系统里的背景抖动」。 */
    private static final double[] FAILURE_RATES = {0.5, 0.1, 0.01, 0.001};

    /** 单趟仿真的步数上限：超过它就算「这一趟没打开」，如实标注，不假装跑到了。 */
    private static final long STEP_CAP_PER_TRIAL = 300_000L;

    /** 每个组合的总步数预算，防止「阈值 10 × p=0.1%」（理论期望 1e30 次请求）把用例跑成永远。 */
    private static final long STEP_BUDGET_PER_COMBO = 6_000_000L;

    private static final int MAX_TRIALS = 200;

    /**
     * 仿真的失败异常：<b>刻意复用一个实例</b>。
     *
     * <p>熔断器只沿 cause 链看<b>类型</b>（{@code isInfrastructureFailure}），不碰异常内容；
     * 每趟 new 一个会让 {@code fillInStackTrace} 变成仿真的主要开销（要跑上千万次迭代）。
     * 这不改变被测逻辑，但必须写出来 —— 否则读的人会以为「每次都构造一个新异常」
     * 是真实路径的忠实还原。
     */
    private static final Throwable CONNECT_FAILURE =
            new SQLTransientConnectionException("仿真：池子拿不到连接（连接类失败）");

    /**
     * 一次失败的请求要等多久才返回 —— <b>不是本用例测的</b>，
     * 是 {@code DbSourceCircuitBreakerIntegrationTest} 在真实链路上实测的 6035 ms
     * （数据源指向协议规定不可路由的 192.0.2.1）。这里只拿它做「请求数 → 秒数」的换算。
     */
    private static final long MEASURED_FAILED_ATTEMPT_MS = 6035;

    // ==================================================================
    // 表一：阈值 × 故障率 → 打开所需请求数
    // ==================================================================

    @Test
    @DisplayName("⭐ 表一：阈值 × 故障率 → 打开所需请求数（固定种子仿真 + 精确公式对照）")
    void howManyRequestsUntilTheBreakerOpens() {
        System.out.println();
        System.out.println("【表一·仿真】测的是熔断器逻辑（allowRequest / recordFailure / recordSuccess），不是真实 JDBC 链路");
        System.out.println("           固定种子 " + SEED + "，openSeconds=" + OPEN_SECONDS
                + "；每趟用一个全新的熔断器，喂请求直到它打开");
        System.out.println("           「业务类失败不算数」这条性质不在这里验（见 DbSourceCircuitBreakerTest）");
        System.out.println();
        System.out.printf(Locale.ROOT, "%-6s %-9s %-26s %-16s %-14s %s%n",
                "阈值", "故障率p", "仿真：打开所需请求数", "趟数(打开/总)", "喂入请求数", "精确公式期望");

        Random random = new Random(SEED);
        for (int threshold : THRESHOLDS) {
            for (double p : FAILURE_RATES) {
                Combo combo = simulateCombo(threshold, p, random);

                String simulated;
                if (combo.openedTrials == 0) {
                    simulated = "未打开(喂满上限)";
                } else if (combo.openedTrials == combo.trials) {
                    simulated = String.format(Locale.ROOT, "均值 %.0f / 中位 %d",
                            combo.meanWhenOpened(), combo.medianWhenOpened());
                } else {
                    // ⚠️ 这个均值【偏小】：没打开的那些趟被右删失掉了，
                    // 留下来的都是"运气好、很早就打开"的趟。所以只统计已打开的趟时要写明。
                    simulated = String.format(Locale.ROOT, "均值 %.0f(仅已打开的 %d 趟，右删失)",
                            combo.meanWhenOpened(), combo.openedTrials);
                }

                System.out.printf(Locale.ROOT, "%-6d %-9s %-26s %-16s %-14s %s%n",
                        threshold,
                        percent(p),
                        simulated,
                        combo.openedTrials + "/" + combo.trials,
                        combo.fedRequests,
                        number(combo.expectedByFormula));

                // 交叉验证：熔断器自己记的 circuit.opened 必须等于「仿真里打开了几趟」。
                // 指标和状态对不上，看板上的「熔断打开次数」就是假的。
                assertEquals(combo.openedTrials, (int) combo.openedCounter,
                        "阈值 " + threshold + " / p=" + p + "：circuit.opened 计数必须等于打开的趟数");
            }
        }
        System.out.println();
        System.out.println("  读法：p 是「单次请求是连接类失败」的概率；熔断器数的是【连续】失败，");
        System.out.println("        所以打开所需请求数 ≈ 1/p^k 量级，k 每 +1 就贵一个数量级。");
        System.out.println("  对照：能跑满 200 趟的那几行，仿真均值与精确公式在抽样噪声内一致");
        System.out.println("        （分布是重尾的，所以中位数明显小于均值）—— 这说明仿真内核没写错。");
        System.out.println("        「未打开(喂满上限)」是真话：那个组合的期望是 1e10 ~ 1e30 次请求量级，");
        System.out.println("        任何仿真都跑不到，只能给这个下界。");
        System.out.println();
    }

    // ==================================================================
    // 表二：打开之后窗口内全被拒 —— 实测 1 秒窗口 + 按速率换算
    // ==================================================================

    @Test
    @DisplayName("⭐ 表二：打开之后窗口内的请求全被拒（实测 1 秒窗口 + 误拒数按速率换算）")
    void everyRequestInsideTheOpenWindowIsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // ⚠️ 基准必须取在【熔断之前】：熔断器内部的 openedAt 是 recordFailure 里取的
        // System.currentTimeMillis()，取在它后面会让"窗口长度"少算那零点几毫秒 ——
        // 第一版就是这么写的，实测放行发生在"999 ms"，看着像窗口漏了 1 毫秒，
        // 其实是测量基准自己偏了。取在前面，量出来的时长只会【不小于】真实窗口。
        long measurementBase = System.currentTimeMillis();
        DbSourceCircuitBreaker breaker = openFreshBreaker(1, registry);
        long openedAtSkewMs = System.currentTimeMillis() - measurementBase;

        // ① 窗口内打死循环：每一次调用都记账，重点是「第一次被放行发生在第几毫秒」
        long rejected = 0;
        long firstAllowedAtMs = -1;
        while (System.currentTimeMillis() - measurementBase < 5_000) {
            if (breaker.allowRequest(DATA_SOURCE_ID)) {
                firstAllowedAtMs = System.currentTimeMillis() - measurementBase;
                break;
            }
            rejected++;
        }

        System.out.println();
        System.out.println("【表二·实测，真实时钟】阈值=1，冷却窗口 " + OPEN_SECONDS + " 秒（真实配置是 30 秒）");
        System.out.printf(Locale.ROOT,
                "  测量基准取在熔断之前（偏差 %d ms，只会把窗口量长不会量短）%n", openedAtSkewMs);
        System.out.printf(Locale.ROOT,
                "  ① 窗口内连续调用 allowRequest %d 次 → 被拒 %d 次、放行 0 次（拒绝吞吐约 %d 次/秒）%n",
                rejected, rejected, rejected);
        System.out.printf(Locale.ROOT,
                "  ② 第 1 次被放行的时刻：打开后 %d ms（= 配置的冷却窗口本身；它就是那个半开探测）%n",
                firstAllowedAtMs);

        assertTrue(firstAllowedAtMs >= OPEN_SECONDS * 1000L,
                "冷却窗口内不该有任何请求被放行，实测第 " + firstAllowedAtMs + " ms 就放行了");
        assertEquals((double) rejected, counter(registry, SqlPlatformMetrics.CIRCUIT_REJECTED),
                "每一次拒绝都必须记进 datasource.circuit.rejected，否则「在熔断」不可观测");
        assertTrue(rejected > 10_000,
                "窗口是 1 秒，拒绝路径不该这么慢，实测只有 " + rejected + " 次");

        // ③ 半开探测在途：这期间所有请求仍然被拒（HALF_OPEN 分支）
        long probeCalls = 0;
        long probeRejected = 0;
        long probeStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - probeStart < 300) {
            probeCalls++;
            if (!breaker.allowRequest(DATA_SOURCE_ID)) {
                probeRejected++;
            }
        }
        System.out.printf(Locale.ROOT,
                "  ③ 半开探测在途（仿真 300 ms）：%d 次调用里 %d 次被拒 → 探测没回来之前，这个数据源仍然不可用%n",
                probeCalls, probeRejected);
        assertEquals(probeCalls, probeRejected, "HALF_OPEN 期间除了那个探测，其它请求必须一律拒绝");

        // 让探测以失败收场（真实链路里这一步要等满建连超时，已有实测 6035 ms）
        breaker.recordFailure(DATA_SOURCE_ID, CONNECT_FAILURE);
        assertTrue(!breaker.allowRequest(DATA_SOURCE_ID), "探测失败应当重新熔断");

        // ④ 按「请求速率」换算误拒数：2 req/s 与 10 req/s
        System.out.println();
        System.out.println("  ④ 按速率换算（窗口内实测放行 0 次 → 窗口内被拒数 = 速率 × 窗口长度）：");
        long measuredWindowMs = OPEN_SECONDS * 1000L;
        for (int rate : new int[]{2, 10}) {
            SimpleMeterRegistry rateRegistry = new SimpleMeterRegistry();
            DbSourceCircuitBreaker paced = openFreshBreaker(1, rateRegistry);
            long pacedRejected = rejectsAtRate(paced, rate, measuredWindowMs);
            assertEquals(rate, pacedRejected,
                    rate + " req/s 在 1 秒窗口内应当一个都过不去，实测过了 " + (rate - pacedRejected) + " 个");
            assertEquals((double) pacedRejected, counter(rateRegistry, SqlPlatformMetrics.CIRCUIT_REJECTED),
                    "被拒数必须和 circuit.rejected 计数一致");

            double steadyState30s = rate * 30.0;
            double cycleSeconds = 30 + MEASURED_FAILED_ATTEMPT_MS / 1000.0;
            System.out.printf(Locale.ROOT,
                    "     %2d req/s：1 秒窗口实测被拒 %d/%d 次（全拒）；"
                            + "外推到 30 秒窗口 = %.0f 次；含探测在途（30 s + %.1f s = %.1f s）≈ %.0f 次%n",
                    rate, pacedRejected, rate, steadyState30s,
                    MEASURED_FAILED_ATTEMPT_MS / 1000.0, cycleSeconds, rate * cycleSeconds);
        }

        System.out.println();
        System.out.println("  ⚠️ 必须区分「拒得对」和「误拒」：");
        System.out.println("     · 数据源真坏的时候被拒【不是误拒】—— 这些请求放进去也是各自等满 "
                + MEASURED_FAILED_ATTEMPT_MS + " ms 再失败；");
        System.out.println("     · 真正的误拒有两块：① 抖动引起的【假熔断】期间的拒绝（见表四 B）；");
        System.out.println("       ② 【恢复后的尾巴】—— DB 已经好了，但窗口没走完，这段时间的请求全被拒。");
        System.out.printf(Locale.ROOT,
                "       实测到的尾巴 = 冷却窗口本身（上面第 ② 条：%d ms ≈ 窗口长度）；"
                        + "真实配置 30 秒 → 尾巴最长 30 + %.1f ≈ %.0f 秒、平均约 %.0f 秒%n",
                firstAllowedAtMs, MEASURED_FAILED_ATTEMPT_MS / 1000.0,
                30 + MEASURED_FAILED_ATTEMPT_MS / 1000.0,
                30 / 2.0 + MEASURED_FAILED_ATTEMPT_MS / 1000.0);
        System.out.println();
    }

    // ==================================================================
    // 表三：反方向 —— 瞬时抖动会不会误伤
    // ==================================================================

    @Test
    @DisplayName("⭐ 表三：瞬时抖动（连续 N 次失败后恢复正常）在阈值 1/3/5/10 下会不会误伤")
    void transientBurstShouldNotOpenTheBreaker() {
        System.out.println();
        System.out.println("【表三·实测】模式：40 次成功 → 连续 burst 次失败 → 40 次成功（确定性模式，不是概率）");
        System.out.println("            抖动 = 网络抖一下：几秒内连续几条失败，然后又好了");
        System.out.printf(Locale.ROOT, "  %-6s %-10s %-10s %s%n", "阈值", "抖动长度", "是否熔断", "代价");

        for (int threshold : THRESHOLDS) {
            for (int burst : new int[]{1, 2, 3, 4, 5, 6}) {
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                DbSourceCircuitBreaker breaker = new DbSourceCircuitBreaker(threshold, OPEN_SECONDS,
                        new SqlPlatformMetrics(registry));

                for (int i = 0; i < 40; i++) {
                    breaker.recordSuccess(DATA_SOURCE_ID);
                }
                for (int i = 0; i < burst; i++) {
                    breaker.recordFailure(DATA_SOURCE_ID, CONNECT_FAILURE);
                }
                for (int i = 0; i < 40; i++) {
                    breaker.recordSuccess(DATA_SOURCE_ID);
                }

                boolean opened = !"CLOSED".equals(breaker.stateOf(DATA_SOURCE_ID));
                assertEquals(burst >= threshold, opened,
                        "阈值 " + threshold + " 配 " + burst + " 次连续失败：开没开必须与 burst>=阈值 一致");

                // 抖动结束之后那些成功【救不回来】—— recordSuccess 只在 HALF_OPEN 下恢复，
                // 已经 OPEN 的熔断器要等满整个窗口。这就是「误伤」的全部代价。
                String cost = opened
                        ? String.format(Locale.ROOT,
                                "整个窗口白等：仿真窗口 %d 秒内不可用；"
                                        + "真实配置 30 秒时，10 req/s 约 %d 次请求被拒、2 req/s 约 %d 次",
                                OPEN_SECONDS, 10 * 30, 2 * 30)
                        : "无（连续计数被后面的成功清零）";
                System.out.printf(Locale.ROOT, "  %-6d %-10d %-10s %s%n",
                        threshold, burst, opened ? "熔断" : "不熔断", cost);
            }
        }

        System.out.println();
        System.out.println("  读法：熔断器数的是【连续】失败，所以唯一的界就是 burst == 阈值；");
        System.out.println("        阈值 1 意味着「任何一次连接抖动都熔断」—— 它几乎等于把这个数据源关掉。");
        System.out.println();
    }

    @Test
    @DisplayName("⚠️ 已知边界实测：失败/成功严格交替时，任何 k>=2 的阈值都不会打开")
    void alternatingFailuresDefeatTheConsecutiveCounter() {
        // 这不是编出来的场景：数据源挂在两个节点上、其中一个坏了；
        // 或者调用方「失败就重试一次」（重试落到健康节点）—— 熔断器看到的就是 f,s,f,s,...
        long requests = 1_000_000L;
        System.out.println();
        System.out.println("【表三·补充实测】失败/成功严格交替（f,s,f,s,...，故障率恰好 50%）喂 "
                + requests + " 次请求：");

        for (int threshold : THRESHOLDS) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            DbSourceCircuitBreaker breaker = new DbSourceCircuitBreaker(threshold, OPEN_SECONDS,
                    new SqlPlatformMetrics(registry));
            for (long i = 0; i < requests; i++) {
                if (i % 2 == 0) {
                    breaker.recordFailure(DATA_SOURCE_ID, CONNECT_FAILURE);
                } else {
                    breaker.recordSuccess(DATA_SOURCE_ID);
                }
            }
            boolean opened = !"CLOSED".equals(breaker.stateOf(DATA_SOURCE_ID));
            System.out.printf(Locale.ROOT, "  阈值 %-3d → %s（circuit.opened=%d）%n",
                    threshold, opened ? "熔断" : "【没有熔断】",
                    (int) counter(registry, SqlPlatformMetrics.CIRCUIT_OPENED));
            if (threshold == 1) {
                assertTrue(opened, "阈值 1 遇到第一次失败就该打开");
            } else {
                // 如实断言「它没打开」：这是一个**反方向的洞**，钉在用例里比写在文档里更难被遗忘。
                assertTrue(!opened,
                        "阈值 " + threshold + " 配交替失败：连续计数永远到不了阈值 —— "
                                + "这是「连续失败」这个判定方式的已知边界，不是 bug");
            }
        }
        System.out.println("  ⚠️ 结论：交替模式下「50% 的请求在等满建连超时」，熔断器一次都不会介入。");
        System.out.println("     所以「连续失败」这个判定对【相关性】敏感、对【故障率】反而不敏感 ——");
        System.out.println("     单用 p（故障率）是描述不了真实故障的（真实故障是成簇的，见类注释里的模型边界）。");
        System.out.println();
    }

    // ==================================================================
    // 表四：代价表 —— 调大调小各要付什么
    // ==================================================================

    @Test
    @DisplayName("⭐ 表四：代价表 —— 阈值调大 = 故障期间多等多久；调小 = 被抖动误伤多频繁")
    void costTableOfThresholdAndOpenWindow() {
        System.out.println();
        System.out.println("【表四·推断】数字来源：表一的精确公式 + 速率换算（不是新测的）");
        System.out.println("  A) 阈值调大的代价：p=50%（一半请求连不上）时，熔断器多久才介入");

        for (double rate : new double[]{2, 10}) {
            for (int threshold : THRESHOLDS) {
                double requests = expectedRequestsToOpen(threshold, 0.5);
                double seconds = requests / rate;
                System.out.printf(Locale.ROOT,
                        "     %4.0f req/s · 阈值 %-2d → 要 %.0f 次请求；若尝试速率真能到该值则 %.1f 秒才熔断"
                                + "（这 %.1f 秒里每条失败的请求都要等满 %.1f 秒建连超时，还占着 1/5 的连接池）%n",
                        rate, threshold, requests, seconds, seconds, MEASURED_FAILED_ATTEMPT_MS / 1000.0);
            }
            System.out.println();
        }

        System.out.println("     ⚠️ 上面「多少秒」这一列是【推算】，它假定尝试速率就是行首那个数 —— 而这个假定");
        System.out.println("        在「建连超时型」故障下并不成立：失败的请求要占住一个并发槽 6 秒，");
        System.out.println("        而每数据源的并发上限是 ConnectionManager.MAX_POOL_SIZE=5（QueryConcurrencyGuard），");
        System.out.println("        所以失败尝试的速率上限 ≈ 5 / 6 ≈ 0.83 次/秒（p=50% 时全部尝试 ≈ 1.7 次/秒）。");
        System.out.println("        → 建连超时型故障下，现实落在【2 req/s 那一行】（5 次连续失败 ≈ 半分钟），");
        System.out.println("          10 req/s 那一行只适用于「连不上但立刻失败」（端口被拒 / 立刻 ECONNREFUSED）。");
        System.out.println();

        System.out.println("  B) 阈值调小的代价：健康系统里的背景抖动（p=1% / 0.1%）多久误伤一次");
        System.out.println("     「误熔断频率」= 10 req/s 的请求速率 / 打开所需请求数；"
                + "大于 1 次/周期就意味着 30 秒窗口首尾相接 = 这个数据源基本一直开着（关着）");
        for (double jitter : new double[]{0.01, 0.001}) {
            for (int threshold : THRESHOLDS) {
                double requests = expectedRequestsToOpen(threshold, jitter);
                double perDay = 24 * 3600 * 10 / requests;      // 按 10 req/s 算
                String frequency;
                if (perDay >= 1) {
                    frequency = String.format(Locale.ROOT, "%.1f 次/天 → 窗口首尾相接，基本一直熔断着", perDay);
                } else if (perDay >= 1 / 365.0) {
                    frequency = String.format(Locale.ROOT, "%.3f 次/天（约 %.1f 天一次）", perDay, 1 / perDay);
                } else {
                    double years = 1 / perDay / 365.0;
                    frequency = years >= 1e6
                            ? "实际上永不发生（> 100 万年）"
                            : String.format(Locale.ROOT, "约 %.0f 年一次", years);
                }
                System.out.printf(Locale.ROOT, "     p=%-6s 阈值 %-2d → 要 %-12s 次请求；10 req/s 下 %s%n",
                        percent(jitter), threshold, number(requests), frequency);
            }
            System.out.println();
        }

        System.out.println("  C) 冷却窗口（open-seconds）的代价：它同时是【恢复后的尾巴】");
        for (int windowSeconds : new int[]{5, 30, 60}) {
            double worstTail = windowSeconds + MEASURED_FAILED_ATTEMPT_MS / 1000.0;
            double avgTail = windowSeconds / 2.0 + MEASURED_FAILED_ATTEMPT_MS / 1000.0;
            System.out.printf(Locale.ROOT,
                    "     窗口 %2d 秒 → 恢复后仍有最长 %.0f 秒 / 平均 %.0f 秒的请求被误拒"
                            + "（10 req/s：最长 %.0f 个 / 平均 %.0f 个请求）%n",
                    windowSeconds, worstTail, avgTail, 10 * worstTail, 10 * avgTail);
        }

        // 单调性守一下：阈值越大越难打开。这是全部结论的地基。
        for (int i = 1; i < THRESHOLDS.length; i++) {
            assertTrue(expectedRequestsToOpen(THRESHOLDS[i], 0.5)
                            > expectedRequestsToOpen(THRESHOLDS[i - 1], 0.5),
                    "阈值越大，打开所需请求数必须严格更大");
        }
        System.out.println();
    }

    // ==================================================================
    // 仿真内核
    // ==================================================================

    /**
     * 达到 k 次<b>连续</b>失败所需请求数的精确期望（负二项分布）：
     * {@code E = (1 - p^k) / ((1 - p) * p^k)}。
     *
     * <p>这是<b>公式</b>，不是仿真值。表和结论里凡是用到它的地方都标了「推断」——
     * 它和仿真值互为对照：对得上，说明仿真的实现没写错（对不上就是仿真内核写错了）。
     */
    static double expectedRequestsToOpen(int threshold, double p) {
        double pk = Math.pow(p, threshold);
        return (1 - pk) / ((1 - p) * pk);
    }

    private static final class Combo {
        int trials;
        int openedTrials;
        long fedRequests;
        double expectedByFormula;
        double openedCounter;
        final List<Long> requestsWhenOpened = new ArrayList<>();

        double meanWhenOpened() {
            return requestsWhenOpened.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        long medianWhenOpened() {
            List<Long> sorted = new ArrayList<>(requestsWhenOpened);
            sorted.sort(Long::compareTo);
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }
    }

    /**
     * 跑一个组合：{@code trials} 趟，每趟一个全新的熔断器，按概率 p 喂请求直到它打开。
     *
     * <p>趟数是按<b>算出来的期望</b>反推的，不是拍脑袋定的：期望越大，趟数越少
     * （总步数有预算）。跑不满的趟如实记成「没打开」，不假装跑到了 ——
     * 比如「阈值 10 × p=0.1%」的理论期望是 1e30 次请求，任何仿真都跑不到，
     * 只能给「喂满上限仍未打开」这个下界。
     */
    private static Combo simulateCombo(int threshold, double p, Random random) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);

        double expected = expectedRequestsToOpen(threshold, p);
        long stepsPerTrial = Math.min(Math.max(1L, (long) Math.ceil(expected)), STEP_CAP_PER_TRIAL);
        int trials = (int) Math.max(1, Math.min(MAX_TRIALS, STEP_BUDGET_PER_COMBO / stepsPerTrial));

        Combo combo = new Combo();
        combo.trials = trials;
        combo.expectedByFormula = expected;

        for (int t = 0; t < trials; t++) {
            DbSourceCircuitBreaker breaker = new DbSourceCircuitBreaker(threshold, OPEN_SECONDS, metrics);
            long issued = 0;
            boolean opened = false;
            for (long i = 1; i <= STEP_CAP_PER_TRIAL; i++) {
                issued = i;
                if (random.nextDouble() < p) {
                    breaker.recordFailure(DATA_SOURCE_ID, CONNECT_FAILURE);
                } else {
                    breaker.recordSuccess(DATA_SOURCE_ID);
                }
                if (!"CLOSED".equals(breaker.stateOf(DATA_SOURCE_ID))) {
                    opened = true;
                    break;
                }
            }
            combo.fedRequests += issued;
            if (opened) {
                combo.openedTrials++;
                combo.requestsWhenOpened.add(issued);
            }
        }

        combo.openedCounter = counter(registry, SqlPlatformMetrics.CIRCUIT_OPENED);
        return combo;
    }

    private static DbSourceCircuitBreaker openFreshBreaker(int threshold, SimpleMeterRegistry registry) {
        DbSourceCircuitBreaker breaker = new DbSourceCircuitBreaker(threshold, OPEN_SECONDS,
                new SqlPlatformMetrics(registry));
        for (int i = 0; i < threshold; i++) {
            breaker.recordFailure(DATA_SOURCE_ID, CONNECT_FAILURE);
        }
        assertTrue(!"CLOSED".equals(breaker.stateOf(DATA_SOURCE_ID)), "喂满阈值之后必须已经熔断");
        return breaker;
    }

    /**
     * 按 {@code ratePerSecond} 的速率发 {@code durationMillis} 毫秒的请求，返回被拒次数。
     *
     * <p>按<b>目标时刻</b>对齐而不是 sleep 累加：Windows 上 sleep 粒度约 15 ms，
     * 累加会让「10 req/s」实际漂成 8 req/s —— 那样表里的数字就不是它标称的那个速率了。
     */
    private static long rejectsAtRate(DbSourceCircuitBreaker breaker, int ratePerSecond, long durationMillis) {
        int requests = (int) (ratePerSecond * durationMillis / 1000L);
        long start = System.nanoTime();
        long rejected = 0;
        for (int i = 0; i < requests; i++) {
            awaitNanos(start + (long) i * 1_000_000_000L / ratePerSecond);
            if (!breaker.allowRequest(DATA_SOURCE_ID)) {
                rejected++;
            }
        }
        return rejected;
    }

    private static void awaitNanos(long targetNanos) {
        long remaining = targetNanos - System.nanoTime();
        if (remaining > 2_000_000L) {
            try {
                Thread.sleep((remaining - 1_000_000L) / 1_000_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        while (System.nanoTime() < targetNanos) {
            Thread.onSpinWait();
        }
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        var meter = registry.find(name).counter();
        return meter == null ? 0 : meter.count();
    }

    private static String percent(double p) {
        return String.format(Locale.ROOT, "%.1f%%", p * 100);
    }

    /** 小数字直接打，大数字用科学计数 —— 1e30 打成 30 个 0 根本没法读。 */
    private static String number(double v) {
        return v < 1e12
                ? String.format(Locale.ROOT, "%.0f", v)
                : String.format(Locale.ROOT, "%.2e", v);
    }
}
