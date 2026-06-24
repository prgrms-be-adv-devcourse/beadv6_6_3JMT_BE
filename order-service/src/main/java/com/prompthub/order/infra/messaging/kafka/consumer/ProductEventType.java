package com.prompthub.order.infra.messaging.kafka.consumer;

import java.util.Arrays;

public enum ProductEventType {
    PRODUCT_STOPPED,
    PRODUCT_DELETED,
    PRODUCT_PRICE_CHANGED,
    UNKNOWN;

    public static ProductEventType from(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
            .filter(eventType -> eventType.name().equals(type))
            .findFirst()
            .orElse(UNKNOWN);
    }
}
