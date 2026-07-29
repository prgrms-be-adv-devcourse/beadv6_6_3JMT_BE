package com.prompthub.settlement.application.dto;

public record SettlementDeliveryAttempt(
        int attemptNumber,
        SellerSettlementRegistrationCommand command) {
}
