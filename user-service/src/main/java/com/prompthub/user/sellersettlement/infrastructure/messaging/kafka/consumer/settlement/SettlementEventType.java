package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import java.util.Arrays;

public enum SettlementEventType {

    SETTLEMENT_CREATED,
    UNKNOWN;

    public static SettlementEventType from(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(eventType -> eventType.name().equals(type))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
