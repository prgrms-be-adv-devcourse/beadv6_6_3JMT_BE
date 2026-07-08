package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedMessage;
import com.prompthub.user.sellersettlement.application.event.SettlementEventEnvelope;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementEventConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private String envelopeJson(String eventType, UUID settlementId) {
        SettlementCreatedMessage payload = new SettlementCreatedMessage(
                settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));
        SettlementEventEnvelope<SettlementCreatedMessage> envelope = new SettlementEventEnvelope<>(
                UUID.randomUUID(), eventType, 1, LocalDateTime.of(2026, 7, 1, 4, 0), settlementId, payload);
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    void settlement_created면_seed하고_ack한다() {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        UUID settlementId = UUID.randomUUID();

        consumer.consume(envelopeJson("settlement.created", settlementId), ack);

        then(useCase).should().seed(any(SettlementCreatedMessage.class));
        then(ack).should().acknowledge();
    }

    @Test
    void 알수없는_이벤트타입은_seed하지않고_ack만() {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);

        consumer.consume(envelopeJson("settlement.unknown", UUID.randomUUID()), ack);

        then(useCase).should(never()).seed(any());
        then(ack).should().acknowledge();
    }
}
