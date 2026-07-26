package com.prompthub.notification.infra.messaging.kafka.event;

import java.util.Arrays;
import java.util.Optional;

public enum OrderEventType {
    ORDER_PAID,
    ORDER_REFUND;

    public static Optional<OrderEventType> from(String value) {
        return Arrays.stream(values())
            .filter(type -> type.name().equals(value))
            .findFirst();
    }
}
