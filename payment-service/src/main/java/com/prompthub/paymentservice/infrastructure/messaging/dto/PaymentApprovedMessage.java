package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentApprovedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String approvedAt
) {}
