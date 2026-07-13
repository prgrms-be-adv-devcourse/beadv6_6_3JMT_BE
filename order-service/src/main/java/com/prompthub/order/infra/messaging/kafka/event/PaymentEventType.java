package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.common.event.EventType;

public enum PaymentEventType implements EventType {

    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_REFUND_COMPLETED,
    PAYMENT_REFUND_FAILED,
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

        String normalizedValue = value.toUpperCase().replace(".", "_");
        for (PaymentEventType type : values()) {
            if (type.name().equals(normalizedValue)) {
                return type;
            }
        }

        return null;
    }
}
