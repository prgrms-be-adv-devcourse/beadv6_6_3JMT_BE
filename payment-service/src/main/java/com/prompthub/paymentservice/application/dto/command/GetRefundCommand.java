package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record GetRefundCommand(UUID paymentId, UUID orderProductId) {}
