package com.prompthub.order.infra.messaging.kafka.consumer;

import java.util.Arrays;

public enum PaymentEventType {
    PAYMENT_APPROVED,
    PAYMENT_FAILED,
    PAYMENT_CANCELED,
    PAYMENT_REFUNDED,
    UNKNOWN;

    public static PaymentEventType from(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
            .filter(eventType -> eventType.name().equals(type))
            .findFirst()
            .orElse(UNKNOWN);
    }
}
