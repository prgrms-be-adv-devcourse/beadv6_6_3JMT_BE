package com.prompthub.payment.infrastructure.external.toss.dto;

public record TossRefundRequest(String cancelReason, Integer cancelAmount) {}
