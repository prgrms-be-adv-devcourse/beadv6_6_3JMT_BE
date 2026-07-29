package com.prompthub.settlement.application.dto;

public record SettlementDeliverySummary(
        int total, int reconciled, int deliveryFailed, int mismatch) {
}
