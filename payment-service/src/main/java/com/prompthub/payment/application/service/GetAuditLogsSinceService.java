package com.prompthub.payment.application.service;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import com.prompthub.payment.application.usecase.GetAuditLogsSinceUseCase;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAuditLogsSinceService implements GetAuditLogsSinceUseCase {

    private static final int MAX_RESULTS = 2000;

    private final AuditLogRepository auditLogRepository;

    @Override
    public List<AuditLogResult> getSince(OffsetDateTime since) {
        return auditLogRepository.findAllCreatedAfter(since, MAX_RESULTS).stream()
            .map(this::toResult)
            .toList();
    }

    private AuditLogResult toResult(AuditLog auditLog) {
        return new AuditLogResult(
            auditLog.getId(), auditLog.getOrderId(), auditLog.getEntityType().name(),
            auditLog.getEntityId(), auditLog.getEventType().name(), auditLog.getActorId(),
            auditLog.getNewStatus(), auditLog.getFailureCode(), auditLog.getDetail(),
            auditLog.getOccurredAt(), auditLog.getCreatedAt()
        );
    }
}
