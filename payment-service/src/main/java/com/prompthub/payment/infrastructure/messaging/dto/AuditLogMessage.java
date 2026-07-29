package com.prompthub.payment.infrastructure.messaging.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogMessage(
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
