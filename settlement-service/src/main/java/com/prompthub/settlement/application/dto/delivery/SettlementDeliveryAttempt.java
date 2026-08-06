package com.prompthub.settlement.application.dto.delivery;

public record SettlementDeliveryAttempt(
        int attemptNumber,
        SellerSettlementRegistrationCommand command) {
}
