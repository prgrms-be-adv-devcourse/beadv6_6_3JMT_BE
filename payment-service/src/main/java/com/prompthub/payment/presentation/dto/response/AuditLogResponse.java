package com.prompthub.payment.presentation.dto.response;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID orderId,
    String entityType,
    UUID entityId,
    String eventType,
    UUID actorId,
    String newStatus,
    String failureCode,
    String detail,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt
) {
    public static AuditLogResponse from(AuditLogResult result) {
        return new AuditLogResponse(
            result.id(), result.orderId(), result.entityType(), result.entityId(),
            result.eventType(), result.actorId(), result.newStatus(), result.failureCode(),
            result.detail(), result.occurredAt(), result.createdAt()
        );
    }
}
