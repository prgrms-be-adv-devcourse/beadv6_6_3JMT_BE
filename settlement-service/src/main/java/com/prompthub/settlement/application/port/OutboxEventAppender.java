package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import java.util.UUID;

public interface OutboxEventAppender {

    void appendSettlementCreated(UUID settlementBatchId, SettlementCreatedPayload payload);
}
