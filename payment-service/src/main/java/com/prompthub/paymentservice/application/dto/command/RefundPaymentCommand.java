package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record RefundPaymentCommand(UUID paymentId, UUID userId) {}
