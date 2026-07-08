package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.event.SettlementCreatedMessage;

public interface SettlementEventPublisher {

    void publishSettlementCreated(SettlementCreatedMessage message);
}
