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

    private SettlementDetail sale(String lineAmount) {
        return SettlementDetail.sale(UUID.randomUUID(),
                new BigDecimal(lineAmount), new BigDecimal("0.15"), OCCURRED_AT);
    }

    @Test
    @DisplayName("Settlement의 정산 스냅샷 10필드를 이벤트로 누락 없이 매핑한다")
    void from_mapsAllSnapshotFields() {
        // given
        UUID settlementId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), sellerId, PERIOD,
                List.of(sale("100.00"), sale("200.00")));
        ReflectionTestUtils.setField(settlement, "id", settlementId); // id는 @GeneratedValue라 unit에선 수동 세팅

        // when
        SettlementCreatedEvent event = SettlementCreatedEvent.from(settlement);

        // then
        assertThat(event.settlementId()).isEqualTo(settlementId);
        assertThat(event.sellerId()).isEqualTo(sellerId);
        assertThat(event.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(event.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 7));
        assertThat(event.productCount()).isEqualTo(2);
        assertThat(event.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(event.settlementTotalAmount()).isEqualByComparingTo("255.00");
        assertThat(event.feeTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(event.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(event.calculatedAt()).isEqualTo(settlement.getCalculatedAt());
    }
}
