package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentFailedMessage(
    UUID orderId
) {}
