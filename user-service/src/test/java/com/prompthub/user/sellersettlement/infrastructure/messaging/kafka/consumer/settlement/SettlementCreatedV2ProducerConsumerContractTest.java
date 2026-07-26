package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementCreatedV2ProducerConsumerContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 실제_producer가_만든_V2_payload는_User_집계_계약을_통과한다() throws Exception {
        UUID orderProductId = UUID.randomUUID();
        SettlementDetail sale = SettlementDetail.sale(
                orderProductId,
                new BigDecimal("100.00"),
                new BigDecimal("0.1500"),
                LocalDateTime.of(2026, 7, 14, 13, 10)
        );
        SettlementDetail refund = SettlementDetail.refund(
                orderProductId,
                new BigDecimal("40.00"),
                new BigDecimal("0.1500"),
                LocalDateTime.of(2026, 7, 17, 9, 20)
        );
        ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        Settlement settlement = Settlement.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SettlementPeriod.of(
                        LocalDate.of(2026, 7, 13),
                        LocalDate.of(2026, 7, 19)
                ),
                List.of(sale, refund)
        );
        ReflectionTestUtils.setField(settlement, "id", UUID.randomUUID());
        SettlementCreatedEvent producerPayload = SettlementCreatedEvent.from(settlement);

        String json = objectMapper.writeValueAsString(producerPayload);
        SettlementCreatedEventV2 consumerPayload =
                objectMapper.readValue(json, SettlementCreatedEventV2.class);

        assertThatCode(consumerPayload::validateContract).doesNotThrowAnyException();
        assertThat(consumerPayload.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(consumerPayload.feeTotalAmount()).isEqualByComparingTo("9.00");
        assertThat(consumerPayload.settlementTotalAmount()).isEqualByComparingTo("51.00");
        assertThat(consumerPayload.refundAmount()).isEqualByComparingTo("40.00");
    }
}
