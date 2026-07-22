package com.prompthub.payment.domain.repository;

import com.prompthub.payment.domain.model.AuditLog;

public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);
}
