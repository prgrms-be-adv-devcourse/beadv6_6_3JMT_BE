package com.prompthub.paymentservice.infrastructure.external.toss.dto;

public record TossConfirmRequest(String paymentKey, String orderId, int amount) {}
