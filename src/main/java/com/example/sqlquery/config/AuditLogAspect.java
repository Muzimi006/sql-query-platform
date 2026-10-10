package com.example.sqlquery.config;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.mapper.AuditLogMapper;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    private final AuditLogMapper auditLogMapper;
    private final SqlPlatformMetrics metrics;

    public AuditLogAspect(AuditLogMapper auditLogMapper, SqlPlatformMetrics metrics) {
        this.auditLogMapper = auditLogMapper;
        this.metrics = metrics;
    }

    @Before("execution(* com.example.sqlquery.controller..*(..))")
    public void auditWriteRequest(JoinPoint joinPoint) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }

        HttpServletRequest request = attributes.getRequest();
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        Long userId = UserContext.get();

        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction(request.getMethod());
            auditLog.setDetail(request.getRequestURI() + " -> " + joinPoint.getSignature().toShortString());
            auditLog.setIp(request.getRemoteAddr());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // ⚠️ 审计写入失败会造出一个**无声的安全审计缺口**：
            //    业务照样成功，而「谁在什么时候做了什么」没了。
            //    光有日志不够（日志会轮转），必须有一个可告警的计数器。
            metrics.recordAuditWriteFailed();
            log.error("审计日志写入失败", e);
        }
        log.info("审计日志 - 用户:{}, 请求方式:{}, 地址:{}, 方法:{}",
                userId,
                request.getMethod(),
                request.getRequestURI(),
                joinPoint.getSignature().toShortString());
    }
}
