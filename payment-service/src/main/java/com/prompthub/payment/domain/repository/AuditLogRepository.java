package com.prompthub.payment.domain.repository;

import com.prompthub.payment.domain.model.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;

public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);

    List<AuditLog> findAllCreatedAfter(OffsetDateTime since, int limit);
}
