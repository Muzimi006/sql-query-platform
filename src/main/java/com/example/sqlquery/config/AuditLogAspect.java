package com.example.sqlquery.config;

import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.entity.AuditLog;
import com.example.sqlquery.mapper.AuditLogMapper;
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

    public AuditLogAspect(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
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
            log.error("审计日志写入失败", e);
        }
        log.info("审计日志 - 用户:{}, 请求方式:{}, 地址:{}, 方法:{}",
                userId,
                request.getMethod(),
                request.getRequestURI(),
                joinPoint.getSignature().toShortString());
    }
}
