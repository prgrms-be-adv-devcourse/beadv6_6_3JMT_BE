package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository extends JpaRepository<AuditLog, UUID> {
}
