package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SettlementCreatedEventListener {

    private final SettlementEventPublisher settlementEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SettlementCreatedPayload payload) {
        settlementEventPublisher.publishSettlementCreated(payload);
    }
}
