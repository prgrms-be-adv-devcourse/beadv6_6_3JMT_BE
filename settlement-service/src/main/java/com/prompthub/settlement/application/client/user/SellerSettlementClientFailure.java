package com.prompthub.settlement.application.client.user;

public record SellerSettlementClientFailure(
        String code,
        boolean retryable) {
}
