package com.prompthub.settlement.application.port;

import java.util.UUID;

public interface SettlementEventPublisher {

    void publish(String topic, UUID aggregateId, String payload);
}
