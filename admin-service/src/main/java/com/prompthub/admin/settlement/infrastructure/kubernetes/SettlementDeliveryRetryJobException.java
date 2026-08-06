package com.prompthub.admin.settlement.infrastructure.kubernetes;

public class SettlementDeliveryRetryJobException extends RuntimeException {

    public SettlementDeliveryRetryJobException(String message) {
        super(message);
    }

    public SettlementDeliveryRetryJobException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
