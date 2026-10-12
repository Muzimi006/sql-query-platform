package com.example.sqlquery.config;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.exception.RejectedBeforeWorkException;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.tx.PlatformWrite;
import com.example.sqlquery.tx.PlatformWriteBuffer;
import com.example.sqlquery.tx.PlatformWriteFlusher;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计：记「谁、什么方式、哪个接口」，以及**这次的结果**。

 * <h2>G-07：它曾经把"被拒绝"也记成了成本</h2>

 * <p>原来的实现是 {@code @Before} —— 在控制器方法**之前**就往主库插一条 audit_log。
 * 而并发闸门在 Service 层（{@code QueryServiceImpl.execute} 的第一句），
 * 于是**一个还没碰到任何资源的请求，先付了一次主库写**。

 * <p>代价是被压测量出来的，不是推测：并发 8 → 64 时，
 * 成功吞吐 296.5 → 87.5 QPS，错误率 14% → 92%。
 * 根因是<b>闸门保护了业务库，成本却记在主库上</b> ——
 * 而主库连接池同样只有 10 条，每个请求还要写 2 张表（审计 + 历史）。

 * <h2>改成了什么</h2>

 * <ol>
 *   <li>{@code @Before} → {@code @Around}：<b>先让请求跑，再按结果记</b>。
 *       这本身也更准确 —— 一条在操作发生<b>之前</b>写的审计记录，
 *       如果操作随后失败了，它记的就是一件没发生的事。</li>
 *   <li>被 {@link RejectedBeforeWorkException}（并发闸门 / 熔断拒绝）拒掉的请求
 *       <b>一条都不写</b>，只记 {@code audit.skipped{reason=rejected-before-work}}。</li>
 *   <li>结果写进 {@code detail}（{@code [SUCCESS]} / {@code [FAILED:异常类名]}），
 *       不需要改表结构 —— 这是有意的：避免为了一列信息引入一次迁移。</li>
 * </ol>

 * <h2>⚠️ 为什么"不写"也要计数</h2>

 * <p>否则「审计少了一条」和「这次请求本来就没有做事」在指标上完全一样。
 * 安全审计最怕的不是少一条，而是<b>说不清为什么少</b>。

 * <h2>⚠️ 它仍然只覆盖"写了什么"，不覆盖"为什么"</h2>

 * <p>审计记录的是"谁调了哪个接口、结果如何"。它<b>不是</b>数据变更审计 ——
 * 真正记录数据变化的是工单链路的前镜像 + 反向 SQL（见档案第十三章）。
 */
@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    /** 不写审计的原因标签（固定常量，不做成动态字符串）。 */
    static final String REASON_REJECTED_BEFORE_WORK = "rejected-before-work";

    private final AuditLogMapper auditLogMapper;
    private final SqlPlatformMetrics metrics;
    private final PlatformWriteBuffer writeBuffer;
    private final PlatformWriteFlusher writeFlusher;

    public AuditLogAspect(AuditLogMapper auditLogMapper, SqlPlatformMetrics metrics,
                          PlatformWriteBuffer writeBuffer, PlatformWriteFlusher writeFlusher) {
        this.auditLogMapper = auditLogMapper;
        this.metrics = metrics;
        this.writeBuffer = writeBuffer;
        this.writeFlusher = writeFlusher;
    }

    @Around("execution(* com.example.sqlquery.controller..*(..))")
    public Object auditWriteRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();

        // GET 不记（读操作量大、且不改变任何东西）；没有请求上下文（定时任务、单测）也不记
        if (request == null || "GET".equalsIgnoreCase(request.getMethod())) {
            return joinPoint.proceed();
        }

        // ⭐ 由最外层开启「平台库写收集」：请求结束时（finally）把审计与查询历史**一次提交**。
        //    背景：一次查询请求原本写两张表、各自提交 = 2 次提交 = 4 次 fsync（双1，口径见档案 G-08）；合并后是 1 次提交。
        //    为什么「减少提交次数」比「关掉双1」划算，见 PlatformWrite 的类注释（有实测数字）。
        boolean owner = writeBuffer.begin();

        try {
            Object result = joinPoint.proceed();
            enqueue(currentUserId(), request, joinPoint, "SUCCESS");
            return result;
        } catch (RejectedBeforeWorkException e) {
            // ⭐ 这一条就是一个资源都没碰到就被拒的请求。
            //    它不是失败，也不该产生任何主库写 —— 但必须留下"为什么没写"的计数。
            metrics.recordAuditSkipped(REASON_REJECTED_BEFORE_WORK);
            log.info("请求在碰到任何资源之前被拒，按设计不写审计。uri={}, reason={}",
                    request.getRequestURI(), e.getMessage());
            throw e;
        } catch (Throwable t) {
            enqueue(currentUserId(), request, joinPoint, "FAILED:" + t.getClass().getSimpleName());
            throw t;
        } finally {
            // ⚠️ 必须在 finally 里收尾：成功、业务失败、被拒绝三条路径都要把缓冲清掉。
            //    Tomcat 线程池会复用线程，残留会被带到下一个请求上（那种 bug 极难复现）。
            if (owner) {
                try {
                    List<PlatformWrite> writes = writeBuffer.drain();
                    if (!writes.isEmpty()) {
                        writeFlusher.flush(writes);
                    }
                } finally {
                    writeBuffer.end();
                }
            }
        }
    }

    /**
     * 把这一条审计**收集起来**（不立刻写库），等请求结束时与其它平台库写一起提交。
     *
     * <p>写库动作被包进 {@link PlatformWrite}：失败时记 {@code audit.write.failed} 并打 ERROR ——
     * 那是一个**无声的安全审计缺口**（业务照样成功，而「谁在什么时候做了什么」没了），
     * 光有日志不够（日志会轮转），必须有一个可告警的计数器。
     */
    private void enqueue(Long userId, HttpServletRequest request, ProceedingJoinPoint joinPoint,
                         String outcome) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(request.getMethod());
        auditLog.setDetail(request.getRequestURI() + " -> "
                + joinPoint.getSignature().toShortString() + " [" + outcome + "]");
        auditLog.setIp(request.getRemoteAddr());

        writeBuffer.add(new PlatformWrite() {
            @Override
            public String name() {
                return "audit_log";
            }

            @Override
            public void write() {
                auditLogMapper.insert(auditLog);
            }

            @Override
            public void onFailure(Exception cause) {
                metrics.recordAuditWriteFailed();
                log.error("审计日志写入失败（期间业务已按成功处理）", cause);
            }
        });

        log.info("审计日志 - 用户:{}, 请求方式:{}, 地址:{}, 方法:{}, 结果:{}",
                userId,
                request.getMethod(),
                request.getRequestURI(),
                joinPoint.getSignature().toShortString(),
                outcome);
    }

    /**
     * 取**当前请求**的用户 id；拿不到时按 {@code null} 记（匿名接口如登录 / 注册）。
     *
     * <p>命名说明：这个方法原来叫 {@code userId()} —— 读起来像一个字段访问器或者局部变量，
     * 而它其实是"从 ThreadLocal 里把当前用户取出来"，**是有动作的**；
     * 而且调用点写着 {@code enqueue(userId(), ...)}，和形参 {@code Long userId} 撞在一起，
     * 一眼看去分不清哪个是"传进去的"、哪个是"现取的"。所以按 Java 惯例改成动词开头。
     */
    private Long currentUserId() {
        try {
            return UserContext.get();
        } catch (Exception e) {
            return null;
        }
    }
}
