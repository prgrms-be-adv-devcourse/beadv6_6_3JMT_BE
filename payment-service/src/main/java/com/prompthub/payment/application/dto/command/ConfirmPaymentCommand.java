package com.prompthub.payment.application.dto.command;

import java.util.UUID;

public record ConfirmPaymentCommand(
    String paymentKey,
    UUID orderId,
    UUID userId,
    int amount
) {}
