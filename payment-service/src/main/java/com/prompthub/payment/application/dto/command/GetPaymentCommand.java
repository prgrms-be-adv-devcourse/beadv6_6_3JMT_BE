package com.prompthub.payment.application.dto.command;

import java.util.UUID;

public record GetPaymentCommand(UUID orderId) {}
