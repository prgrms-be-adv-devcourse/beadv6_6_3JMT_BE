package com.prompthub.settlement.application.client.user;

public class SellerSettlementClientException extends RuntimeException {

    private final SellerSettlementClientFailure failure;

    public SellerSettlementClientException(
            SellerSettlementClientFailure failure,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public SellerSettlementClientFailure getFailure() {
        return failure;
    }
}
