package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.common.event.EventType;

public enum PaymentEventType implements EventType {

    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_FAILED,
    PAYMENT_CANCELED;

    @Override
    public String code() {
        return name();
    }

    public static PaymentEventType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        for (PaymentEventType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }

        return null;
    }
}
