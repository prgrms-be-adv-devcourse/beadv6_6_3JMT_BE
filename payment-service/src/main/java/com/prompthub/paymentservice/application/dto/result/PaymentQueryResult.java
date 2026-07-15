package com.prompthub.paymentservice.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentQueryResult(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    String status,
    Integer amount,
    OffsetDateTime approvedAt,
    OffsetDateTime failedAt
) {}
