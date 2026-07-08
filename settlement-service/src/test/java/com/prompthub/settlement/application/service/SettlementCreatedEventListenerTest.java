package com.prompthub.settlement.application.service;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.event.SettlementCreatedMessage;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementCreatedEventListenerTest {

    @Test
    @DisplayName("SettlementCreatedMessage 수신 시 발행 포트에 위임한다")
    void on_delegatesToPublisher() {
        SettlementEventPublisher publisher = mock(SettlementEventPublisher.class);
        SettlementCreatedEventListener listener = new SettlementCreatedEventListener(publisher);
        SettlementCreatedMessage message = new SettlementCreatedMessage(
                UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));

        listener.on(message);

        then(publisher).should().publishSettlementCreated(message);
    }
}
