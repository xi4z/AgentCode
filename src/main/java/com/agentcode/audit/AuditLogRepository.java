package com.agentcode.audit;

import com.agentcode.entity.AuditLog;
import com.agentcode.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计日志落库入口。落库失败只记录 warn，不阻塞 AI 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogRepository {

    private final AuditLogMapper auditLogMapper;

    public void save(AuditLog auditLog) {
        try {
            if (auditLog != null) {
                auditLogMapper.insert(auditLog);
            }
        } catch (Exception e) {
            log.warn("AUDIT_PERSIST_FAILED type={} error={}", auditLog == null ? null : auditLog.getType(), e.getMessage());
        }
    }
}