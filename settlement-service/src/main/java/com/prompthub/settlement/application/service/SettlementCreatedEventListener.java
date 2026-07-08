package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.event.SettlementCreatedMessage;
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
    public void on(SettlementCreatedMessage message) {
        settlementEventPublisher.publishSettlementCreated(message);
    }
}
