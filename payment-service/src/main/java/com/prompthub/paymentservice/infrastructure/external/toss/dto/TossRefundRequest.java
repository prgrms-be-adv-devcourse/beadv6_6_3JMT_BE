package com.prompthub.paymentservice.infrastructure.external.toss.dto;

public record TossRefundRequest(String cancelReason, Integer cancelAmount) {}
