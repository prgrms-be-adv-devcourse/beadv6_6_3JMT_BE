package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.common.event.EventType;

public enum PaymentEventType implements EventType {

    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_FAILED;

    @Override
    public String code() {
        return name();
    }
}
