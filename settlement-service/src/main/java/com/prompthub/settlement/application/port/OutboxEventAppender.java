package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import java.util.UUID;

public interface OutboxEventAppender {

    void appendSettlementCreated(UUID settlementBatchId, SettlementCreatedEvent event);
}
