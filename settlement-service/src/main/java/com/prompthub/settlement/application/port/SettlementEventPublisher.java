package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.event.SettlementCreatedPayload;

public interface SettlementEventPublisher {

    void publishSettlementCreated(SettlementCreatedPayload payload);
}
