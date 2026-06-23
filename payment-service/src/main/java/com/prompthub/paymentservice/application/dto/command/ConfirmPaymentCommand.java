package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record ConfirmPaymentCommand(
    String paymentKey,
    UUID orderId,
    int amount,
    UUID userId
) {}
