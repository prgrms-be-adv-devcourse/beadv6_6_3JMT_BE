package com.prompthub.settlement.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import com.prompthub.settlement.domain.model.OutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JsonOutboxEventAppenderTest {

    private static final UUID BATCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SETTLEMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SELLER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    private ObjectMapper objectMapper;
    private OutboxEventRepository repository;
    private JsonOutboxEventAppender appender;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        repository = mock(OutboxEventRepository.class);
        appender = new JsonOutboxEventAppender(objectMapper, repository, "settlement-events");
    }

    @Test
    @DisplayName("정산 생성 이벤트는 완성된 EventMessage JSON과 동일한 eventId로 저장된다")
    void appendSettlementCreated_savesStableEnvelope() throws Exception {
        // given
        SettlementCreatedPayload payload = payload();

        // when
        appender.appendSettlementCreated(BATCH_ID, payload);

        // then
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(repository).should().save(captor.capture());
        OutboxEvent event = captor.getValue();
        JsonNode json = objectMapper.readTree(event.getPayload());

        assertThat(json.path("eventId").stringValue()).isEqualTo(event.getEventId().toString());
        assertThat(json.path("eventType").stringValue()).isEqualTo("SETTLEMENT_CREATED");
        assertThat(json.path("aggregateType").stringValue()).isEqualTo("SETTLEMENT");
        assertThat(json.path("aggregateId").stringValue()).isEqualTo(SETTLEMENT_ID.toString());
        assertThat(json.path("payload").path("settlementId").stringValue())
                .isEqualTo(SETTLEMENT_ID.toString());
        assertThat(event.getSettlementBatchId()).isEqualTo(BATCH_ID);
        assertThat(event.getAggregateId()).isEqualTo(SETTLEMENT_ID);
        assertThat(event.getTopic()).isEqualTo("settlement-events");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(json.has("occurredAt")).isTrue();
    }

    private SettlementCreatedPayload payload() {
        return new SettlementCreatedPayload(
                SETTLEMENT_ID,
                SELLER_ID,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                2,
                new BigDecimal("30000.00"),
                new BigDecimal("27000.00"),
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                LocalDateTime.of(2026, 7, 1, 1, 0));
    }
}
