package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEvent;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementEventConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private String eventMessageJson(String eventType, UUID settlementId) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "occurredAt": "2026-07-01T04:00:00",
                  "aggregateType": "SETTLEMENT",
                  "aggregateId": "%s",
                  "payload": {
                    "settlementId": "%s",
                    "sellerId": "00000000-0000-0000-0000-000000000001",
                    "periodStart": "2026-06-01",
                    "periodEnd": "2026-06-30",
                    "productCount": 1,
                    "totalAmount": 100.00,
                    "settlementTotalAmount": 85.00,
                    "feeTotalAmount": 15.00,
                    "refundAmount": 0,
                    "calculatedAt": "2026-07-01T04:00:00"
                  }
                }
                """.formatted(UUID.randomUUID(), eventType, settlementId, settlementId);
    }

    @Test
    void SETTLEMENT_CREATED면_seed하고_ack한다() {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        UUID settlementId = UUID.randomUUID();

        consumer.consume(eventMessageJson("SETTLEMENT_CREATED", settlementId), ack);

        then(useCase).should().seed(any(SettlementCreatedEvent.class));
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
