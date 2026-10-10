package com.example.sqlquery.config;

import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.exception.RejectedBeforeWorkException;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计切面，守的是 <b>G-07</b>：被拒绝的请求不该产生主库写。
 *
 * <h2>为什么这一条值得单独有用例</h2>
 *
 * <p>原来的实现是 {@code @Before} —— 请求还没进控制器就先往主库插一条。
 * 而并发闸门在 Service 层，于是<b>一个什么都没做的请求先付了一次主库写</b>。
 * 压测实测：并发 8 → 64 时成功吞吐 296.5 → 87.5 QPS、错误率 14% → 92%，
 * 根因就是「闸门保护了业务库，成本记在主库上」。
 *
 * <p>这里同时钉住另外两件事：
 * <ul>
 *   <li>结果要写进审计（{@code [SUCCESS]} / {@code [FAILED:类名]}）——
 *       一条写在操作**之前**的记录，在操作失败时记的是一件没发生的事；</li>
 *   <li>「不写」必须有计数（{@code audit.skipped}）——
 *       否则「审计少了一条」和「本来就没做事」在指标上完全一样。</li>
 * </ul>
 */
class AuditLogAspectTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqlPlatformMetrics metrics = new SqlPlatformMetrics(registry);
    private AuditLogMapper auditLogMapper;
    private AuditLogAspect aspect;
    private ProceedingJoinPoint joinPoint;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        auditLogMapper = Mockito.mock(AuditLogMapper.class);
        aspect = new AuditLogAspect(auditLogMapper, metrics);

        request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/api/query/execute");
        Mockito.when(request.getRemoteAddr()).thenReturn("192.0.2.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        Signature signature = Mockito.mock(Signature.class);
        Mockito.when(signature.toShortString()).thenReturn("QueryController.execute(..)");
        Mockito.when(joinPoint.getSignature()).thenReturn(signature);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private double skipped() {
        var counter = registry.find(SqlPlatformMetrics.AUDIT_SKIPPED)
                .tag("reason", AuditLogAspect.REASON_REJECTED_BEFORE_WORK).counter();
        return counter == null ? 0 : counter.count();
    }

    private String capturedDetail() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        Mockito.verify(auditLogMapper).insert(captor.capture());
        return captor.getValue().getDetail();
    }

    @Test
    @DisplayName("GET 不写审计（读操作量大，且不改变任何东西）")
    void getShouldNotBeAudited() throws Throwable {
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.auditWriteRequest(joinPoint));

        Mockito.verifyNoInteractions(auditLogMapper);
    }

    @Test
    @DisplayName("成功之后写审计，并把结果记进 detail")
    void successShouldBeAuditedWithOutcome() throws Throwable {
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(joinPoint.proceed()).thenReturn("ok");

        aspect.auditWriteRequest(joinPoint);

        assertTrue(capturedDetail().endsWith("[SUCCESS]"),
                "审计必须记下结果 —— 写在操作之前的记录在失败时记的是一件没发生的事");
    }

    @Test
    @DisplayName("业务失败也要留痕，并标出异常类型")
    void failureShouldBeAuditedAndRethrown() throws Throwable {
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(joinPoint.proceed())
                .thenThrow(new BusinessException("SQL执行失败：语法错误"));

        assertThrows(BusinessException.class, () -> aspect.auditWriteRequest(joinPoint));

        assertTrue(capturedDetail().endsWith("[FAILED:BusinessException]"),
                "实际：" + capturedDetail());
    }

    @Test
    @DisplayName("⭐ G-07：被闸门/熔断拒掉的请求**一条审计都不写**，但必须留计数")
    void rejectedBeforeWorkShouldNotBeAudited() throws Throwable {
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(joinPoint.proceed())
                .thenThrow(new RejectedBeforeWorkException("你同时执行的查询太多了（上限 3 条）"));

        assertThrows(RejectedBeforeWorkException.class,
                () -> aspect.auditWriteRequest(joinPoint));

        // 这就是 G-07 的修法：这个请求一个资源都没碰到，
        // 而那条 audit_log 会写进**主库** —— 闸门保护了业务库，成本不该记在主库上。
        Mockito.verifyNoInteractions(auditLogMapper);
        assertEquals(1.0, skipped(),
                "「不写」必须留计数，否则说不出为什么少了一条审计");
    }

    @Test
    @DisplayName("审计写库失败不影响业务（只记计数器 + ERROR 日志）")
    void auditWriteFailureShouldNotBreakBusiness() throws Throwable {
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(joinPoint.proceed()).thenReturn("ok");
        Mockito.when(auditLogMapper.insert(ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("平台库不可用"));

        assertEquals("ok", aspect.auditWriteRequest(joinPoint));

        // 业务不受影响，但缺口必须有可告警的信号（这里用的是真指标注册表，不是 mock）
        assertEquals(1.0, registry.find(SqlPlatformMetrics.AUDIT_WRITE_FAILED).counter().count());
    }
}
