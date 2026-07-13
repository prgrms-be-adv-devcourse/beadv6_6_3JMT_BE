package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.common.event.EventMessage;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedPayload;
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

    private String eventMessageJson(String eventType, UUID settlementId) {
        SettlementCreatedPayload payload = new SettlementCreatedPayload(
                settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));
        EventMessage<SettlementCreatedPayload> message = new EventMessage<>(
                UUID.randomUUID(), eventType, LocalDateTime.of(2026, 7, 1, 4, 0),
                "SETTLEMENT", settlementId, payload);
        return objectMapper.writeValueAsString(message);
    }

    @Test
    void SETTLEMENT_CREATED면_seed하고_ack한다() {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        UUID settlementId = UUID.randomUUID();

        consumer.consume(eventMessageJson("SETTLEMENT_CREATED", settlementId), ack);

        then(useCase).should().seed(any(SettlementCreatedPayload.class));
        then(ack).should().acknowledge();
    }

    @Test
    void 알수없는_이벤트타입은_seed하지않고_ack만() {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);

        consumer.consume(eventMessageJson("SETTLEMENT_UNKNOWN", UUID.randomUUID()), ack);

        then(useCase).should(never()).seed(any());
        then(ack).should().acknowledge();
    }
}
