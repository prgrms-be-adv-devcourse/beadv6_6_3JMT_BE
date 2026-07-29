package com.prompthub.payment.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResult(
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
) {}
