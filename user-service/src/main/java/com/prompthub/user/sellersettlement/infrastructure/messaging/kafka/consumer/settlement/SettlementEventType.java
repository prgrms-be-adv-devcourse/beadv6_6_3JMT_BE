package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import java.util.Arrays;

public enum SettlementEventType {

    SETTLEMENT_CREATED("settlement.created"),
    UNKNOWN("");

    private final String type;

    SettlementEventType(String type) {
        this.type = type;
    }

    public static SettlementEventType from(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(eventType -> eventType.type.equals(type))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
