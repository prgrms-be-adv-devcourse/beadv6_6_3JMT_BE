package com.prompthub.payment.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentApprovedMessage(
    UUID orderId,
    int approvedAmount,
    String approvedAt
) {}
