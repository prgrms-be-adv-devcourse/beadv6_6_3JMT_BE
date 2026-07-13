package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.common.event.EventType;

public enum PaymentEventType implements EventType {

    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_REFUND_FAILED,
    PAYMENT_FAILED;

    @Override
    public String code() {
        return name();
    }
}
