package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(OffsetDateTime since, Pageable pageable);
}
