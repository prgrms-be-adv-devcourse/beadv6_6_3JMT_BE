package com.prompthub.settlement.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import com.prompthub.settlement.application.event.SettlementDetailEvent;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
    private static final UUID SALE_DETAIL_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_PRODUCT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID REFUND_DETAIL_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");

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
        SettlementCreatedEvent createdEvent = event();

        // when
        appender.appendSettlementCreated(BATCH_ID, createdEvent);

        // then
        ArgumentCaptor<SettlementOutboxEvent> captor =
                ArgumentCaptor.forClass(SettlementOutboxEvent.class);
        then(repository).should().save(captor.capture());
        SettlementOutboxEvent event = captor.getValue();
        JsonNode json = objectMapper.readTree(event.getPayload());

        assertThat(json.path("eventId").stringValue()).isEqualTo(event.getEventId().toString());
        assertThat(json.path("eventType").stringValue()).isEqualTo("SETTLEMENT_CREATED");
        assertThat(json.path("aggregateType").stringValue()).isEqualTo("SETTLEMENT");
        assertThat(json.path("aggregateId").stringValue()).isEqualTo(SETTLEMENT_ID.toString());
        assertThat(json.path("payload").path("settlementId").stringValue())
                .isEqualTo(SETTLEMENT_ID.toString());
        assertThat(json.path("payload").path("payloadVersion").intValue()).isEqualTo(2);
        assertThat(json.path("payload").path("details").size()).isEqualTo(2);
        JsonNode detail = json.path("payload").path("details").get(1);
        assertThat(detail.path("settlementDetailId").stringValue()).isEqualTo(REFUND_DETAIL_ID.toString());
        assertThat(detail.path("orderProductId").stringValue()).isEqualTo(ORDER_PRODUCT_ID.toString());
        assertThat(detail.path("lineType").stringValue()).isEqualTo("REFUND");
        assertThat(detail.path("lineAmount").decimalValue()).isEqualByComparingTo("-40.00");
        assertThat(detail.path("feeRate").decimalValue()).isEqualByComparingTo("0.1500");
        assertThat(detail.path("feeAmount").decimalValue()).isEqualByComparingTo("-6.00");
        assertThat(detail.path("lineSettlementAmount").decimalValue()).isEqualByComparingTo("-34.00");
        assertThat(detail.path("occurredAt").stringValue()).isEqualTo("2026-06-15T10:00:00");
        assertThat(event.getSettlementBatchId()).isEqualTo(BATCH_ID);
        assertThat(event.getAggregateId()).isEqualTo(SETTLEMENT_ID);
        assertThat(event.getTopic()).isEqualTo("settlement-events");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(json.has("occurredAt")).isTrue();
    }

    private SettlementCreatedEvent event() {
        return new SettlementCreatedEvent(
                2,
                SETTLEMENT_ID,
                SELLER_ID,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                1,
                new BigDecimal("100.00"),
                new BigDecimal("51.00"),
                new BigDecimal("9.00"),
                new BigDecimal("40.00"),
                LocalDateTime.of(2026, 7, 1, 1, 0),
                List.of(
                        new SettlementDetailEvent(
                                SALE_DETAIL_ID,
                                ORDER_PRODUCT_ID,
                                "SALE",
                                new BigDecimal("100.00"),
                                new BigDecimal("0.1500"),
                                new BigDecimal("15.00"),
                                new BigDecimal("85.00"),
                                LocalDateTime.of(2026, 6, 14, 10, 0)),
                        new SettlementDetailEvent(
                                REFUND_DETAIL_ID,
                                ORDER_PRODUCT_ID,
                                "REFUND",
                                new BigDecimal("-40.00"),
                                new BigDecimal("0.1500"),
                                new BigDecimal("-6.00"),
                                new BigDecimal("-34.00"),
                                LocalDateTime.of(2026, 6, 15, 10, 0))));
    }
}
