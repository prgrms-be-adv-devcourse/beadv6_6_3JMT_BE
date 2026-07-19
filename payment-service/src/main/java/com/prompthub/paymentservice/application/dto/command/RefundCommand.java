package com.prompthub.paymentservice.application.dto.command;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundCommand(
    UUID orderId,
    UUID refundRequestId,
    int refundAmount,
    OffsetDateTime requestedAt
) {}
