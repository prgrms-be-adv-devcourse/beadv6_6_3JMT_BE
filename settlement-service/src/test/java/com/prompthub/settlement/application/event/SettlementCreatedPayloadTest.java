package com.prompthub.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCreatedPayloadTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    private SettlementDetail sale(String lineAmount) {
        return SettlementDetail.sale(UUID.randomUUID(),
                new BigDecimal(lineAmount), new BigDecimal("0.15"), OCCURRED_AT);
    }

    @Test
    @DisplayName("Settlement의 정산 스냅샷 10필드를 payload로 누락 없이 매핑한다")
    void from_mapsAllSnapshotFields() {
        // given
        UUID settlementId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), sellerId, YearMonth.of(2026, 6),
                List.of(sale("100.00"), sale("200.00")));
        ReflectionTestUtils.setField(settlement, "id", settlementId); // id는 @GeneratedValue라 unit에선 수동 세팅

        // when
        SettlementCreatedPayload payload = SettlementCreatedPayload.from(settlement);

        // then
        assertThat(payload.settlementId()).isEqualTo(settlementId);
        assertThat(payload.sellerId()).isEqualTo(sellerId);
        assertThat(payload.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(payload.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(payload.productCount()).isEqualTo(2);
        assertThat(payload.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(payload.settlementTotalAmount()).isEqualByComparingTo("255.00");
        assertThat(payload.feeTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(payload.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payload.calculatedAt()).isEqualTo(settlement.getCalculatedAt());
    }
}
