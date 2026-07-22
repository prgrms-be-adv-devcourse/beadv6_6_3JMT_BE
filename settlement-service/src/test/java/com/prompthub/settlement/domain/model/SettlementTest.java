package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);
    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));

    private SettlementDetail detail(String lineAmount, String feeRate) {
        return SettlementDetail.sale(UUID.randomUUID(),
                new BigDecimal(lineAmount), new BigDecimal(feeRate), OCCURRED_AT);
    }

    @Test
    @DisplayName("정산 생성 시 상세 목록에서 건수·총액·수수료·실정산액을 계산한다")
    void create_calculatesTotalsFromDetails() {
        // given : fee 15.00/30.00, settlement 85.00/170.00
        List<SettlementDetail> details = List.of(detail("100.00", "0.15"), detail("200.00", "0.15"));

        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), PERIOD, details);

        // then
        assertThat(settlement.getProductCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("255.00");
    }

    @Test
    @DisplayName("판매와 환불을 분리해 순수수료와 지급액을 계산한다")
    void create_calculatesSaleRefundAndNetFee() {
        // given
        UUID orderProductId = UUID.randomUUID();
        List<SettlementDetail> details = List.of(
                SettlementDetail.sale(
                        orderProductId,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.1500"),
                        LocalDateTime.of(2026, 7, 14, 13, 10)),
                SettlementDetail.refund(
                        orderProductId,
                        new BigDecimal("40.00"),
                        new BigDecimal("0.1500"),
                        LocalDateTime.of(2026, 7, 17, 9, 20)));

        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), PERIOD, details);

        // then
        assertThat(settlement.getProductCount()).isEqualTo(1);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(settlement.getRefundAmount()).isEqualByComparingTo("40.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("9.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("51.00");
    }

    @Test
    @DisplayName("정산 생성 시 환불액 0과 계산 시각이 강제된다")
    void create_forcesRefundZeroAndCalculatedAt() {
        // given
        List<SettlementDetail> details = List.of(detail("100.00", "0.15"));

        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), PERIOD, details);

        // then
        assertThat(settlement.getRefundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getCalculatedAt()).isNotNull();
    }

    @Test
    @DisplayName("정산 기간은 주간 포함 시작일과 종료일로 설정된다")
    void create_setsPeriodFromWeeklyPeriod() {
        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), PERIOD,
                List.of(detail("100.00", "0.15")));

        // then
        assertThat(settlement.getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(settlement.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 7));
    }

    @Test
    @DisplayName("상세가 없으면 건수 0, 합계는 모두 0이다")
    void create_emptyDetails_zeroTotals() {
        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), PERIOD, List.of());

        // then
        assertThat(settlement.getProductCount()).isZero();
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
