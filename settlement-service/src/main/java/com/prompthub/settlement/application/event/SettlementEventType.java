package com.prompthub.settlement.application.event;

import com.prompthub.common.event.EventType;

public enum SettlementEventType implements EventType {

    SETTLEMENT_CREATED;

    @Override
    public String code() {
        return name();
    }
}
