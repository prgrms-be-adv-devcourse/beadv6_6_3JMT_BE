package com.prompthub.admin.settlement.infrastructure.kubernetes;

public class SettlementDeliveryRetryJobAlreadyExistsException
        extends SettlementDeliveryRetryJobException {

    public SettlementDeliveryRetryJobAlreadyExistsException(String message) {
        super(message);
    }

    public SettlementDeliveryRetryJobAlreadyExistsException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
