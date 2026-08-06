package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    @Override
    public AuditLog save(AuditLog auditLog) {
        return jpaRepository.save(auditLog);
    }

    @Override
    public List<AuditLog> findAllCreatedAfter(OffsetDateTime since, int limit) {
        return jpaRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(since, PageRequest.of(0, limit));
    }
}
