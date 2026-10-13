package com.example.sqlquery.infra;

import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器的行为 —— 特别是<b>它到底认不认得出"基础设施失败"</b>。
 *
 * <h2>为什么要专门写这条用例</h2>
 *
 * <p>熔断器是这个项目"三层保护"里的第二层，文档里一直把它当作已有能力讲。
 * 但审计发现：**它在真实调用链上永远不会打开** ——
 * {@code QueryServiceImpl} 把 {@code SQLException} 包成 {@code BusinessException} 时
 * <b>把 cause 丢掉了</b>，而熔断器是靠"沿 cause 链找连接类异常"判断的，
 * 于是它拿到的永远是一个 cause 为 null 的异常 → 判定恒为"不是基础设施失败"。
 *
 * <p>这条用例把这个不变量钉死：**带 cause 的包装异常必须仍然被认出是基础设施失败。**
 * 谁要是再把 cause 丢掉（或者给 BusinessException 换个构造器），这里会立刻红。
 */
class DbSourceCircuitBreakerTest {

    private static final Long DATA_SOURCE_ID = 1L;

    /**
     * 阈值 3、熔断 60 秒（和默认配置一致，这里显式写出来让用例自解释）。
     *
     * <p>metrics 用真的 {@code SimpleMeterRegistry} 而不是 mock：
     * 这个类要验的是"熔断器认不认得出基础设施失败"，指标只是记录，
     * 用真实现能顺带保证计数那条路径不会抛异常。
     */
    private DbSourceCircuitBreaker breaker() {
        return new DbSourceCircuitBreaker(3, 60_000, new SqlPlatformMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("⭐ 连不上 → 连续失败到阈值 → 熔断真的打开")
    void connectionFailureShouldOpenTheBreaker() {
        DbSourceCircuitBreaker breaker = breaker();

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(DATA_SOURCE_ID, new SQLTransientConnectionException("池子拿不到连接"));
        }

        assertFalse(breaker.allowRequest(DATA_SOURCE_ID), "连续 3 次连接类失败之后必须熔断");
    }

    @Test
    @DisplayName("⭐⭐ 被 BusinessException 包过、但带着 cause —— 依然必须认出是基础设施失败")
    void wrappedFailureShouldStillOpenTheBreaker() {
        // 这一条就是审计发现的回归点：以前 BusinessException **不带 cause**，
        // 于是 QueryServiceImpl 包装完之后熔断器完全认不出来 → 永远不打开。
        DbSourceCircuitBreaker breaker = breaker();

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(DATA_SOURCE_ID, new BusinessException("SQL执行失败：连接中断",
                    new SQLTransientConnectionException("池子拿不到连接")));
        }

        assertFalse(breaker.allowRequest(DATA_SOURCE_ID),
                "包装异常带着 cause 时，熔断器必须还能认出底层是连接类失败；"
                        + "认不出来就等于熔断器是死的");
    }

    @Test
    @DisplayName("两层的包装也能认出来（沿 cause 链一路找）")
    void nestedWrappingShouldBeRecognised() {
        DbSourceCircuitBreaker breaker = breaker();

        Throwable deeplyWrapped = new BusinessException("最外层",
                new BusinessException("中间层", new ConnectException("Connection refused")));
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(DATA_SOURCE_ID, deeplyWrapped);
        }

        assertFalse(breaker.allowRequest(DATA_SOURCE_ID));
    }

    @Test
    @DisplayName("用户写错 SQL（业务失败）绝不能熔断数据源")
    void businessFailureShouldNotOpenTheBreaker() {
        DbSourceCircuitBreaker breaker = breaker();

        // 这条守的是"熔断器没有过度反应"：
        // 连续写错 3 次 SQL 就把整个数据源熔断掉，别人也用不了了。
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure(DATA_SOURCE_ID, new BusinessException("Unknown column 'x'"));
        }

        assertTrue(breaker.allowRequest(DATA_SOURCE_ID), "业务失败不该触发熔断");
    }

    @Test
    @DisplayName("没有失败过的数据源永远是放行的")
    void freshDataSourceShouldBeAllowed() {
        assertTrue(breaker().allowRequest(DATA_SOURCE_ID));
    }
}