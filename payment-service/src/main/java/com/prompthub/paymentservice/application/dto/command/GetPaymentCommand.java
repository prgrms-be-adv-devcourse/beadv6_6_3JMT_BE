package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record GetPaymentCommand(UUID orderId) {}
