package com.prompthub.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCreatedEventTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);
    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));

    @Test
    @DisplayName("Settlement의 V2 스냅샷과 전체 상세를 원본 값 그대로 매핑한다")
    void from_mapsV2SnapshotAndAllDetails() {
        // given
        UUID settlementId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID saleDetailId = UUID.randomUUID();
        UUID refundDetailId = UUID.randomUUID();
        SettlementDetail sale = SettlementDetail.sale(
                orderProductId,
                new BigDecimal("100.00"),
                new BigDecimal("0.1500"),
                OCCURRED_AT);
        SettlementDetail refund = SettlementDetail.refund(
                orderProductId,
                new BigDecimal("40.00"),
                new BigDecimal("0.1500"),
                OCCURRED_AT.plusDays(1));
        ReflectionTestUtils.setField(sale, "id", saleDetailId);
        ReflectionTestUtils.setField(refund, "id", refundDetailId);
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), sellerId, PERIOD,
                List.of(sale, refund));
        ReflectionTestUtils.setField(settlement, "id", settlementId); // id는 @GeneratedValue라 unit에선 수동 세팅

        // when
        SettlementCreatedEvent event = SettlementCreatedEvent.from(settlement);

        // then
        assertThat(event.payloadVersion()).isEqualTo(2);
        assertThat(event.settlementId()).isEqualTo(settlementId);
        assertThat(event.sellerId()).isEqualTo(sellerId);
        assertThat(event.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(event.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 7));
        assertThat(event.productCount()).isEqualTo(1);
        assertThat(event.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(event.settlementTotalAmount()).isEqualByComparingTo("51.00");
        assertThat(event.feeTotalAmount()).isEqualByComparingTo("9.00");
        assertThat(event.refundAmount()).isEqualByComparingTo("40.00");
        assertThat(event.calculatedAt()).isEqualTo(settlement.getCalculatedAt());
        assertThat(event.details()).hasSize(2);
        assertThat(event.details()).extracting(SettlementDetailEvent::settlementDetailId)
                .containsExactly(saleDetailId, refundDetailId);
        assertThat(event.details()).extracting(SettlementDetailEvent::lineType)
                .containsExactly("SALE", "REFUND");
        assertThat(event.details().get(1).lineAmount()).isEqualByComparingTo("-40.00");
        assertThat(event.details().get(1).feeAmount()).isEqualByComparingTo("-6.00");
    }
}
