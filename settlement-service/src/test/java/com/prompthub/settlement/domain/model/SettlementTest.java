package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

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
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6), details);

        // then
        assertThat(settlement.getProductCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("45.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("255.00");
    }

    @Test
    @DisplayName("정산 생성 시 초기 상태와 환불액이 강제된다")
    void create_initialStatuses() {
        // given
        List<SettlementDetail> details = List.of(detail("100.00", "0.15"));

        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6), details);

        // then
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.NOT_READY);
        assertThat(settlement.getRefundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getCalculatedAt()).isNotNull();
    }

    @Test
    @DisplayName("정산 기간은 정산 월의 1일부터 말일까지로 설정된다")
    void create_setsPeriodFromYearMonth() {
        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6),
                List.of(detail("100.00", "0.15")));

        // then
        assertThat(settlement.getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(settlement.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("상세가 없으면 건수 0, 합계는 모두 0이다")
    void create_emptyDetails_zeroTotals() {
        // when
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6), List.of());

        // then
        assertThat(settlement.getProductCount()).isZero();
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Settlement pendingSettlement() {
        return Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6),
                List.of(detail("100.00", "0.15")));
    }

    @Test
    @DisplayName("승인: PENDING_APPROVAL 정산을 승인하면 APPROVED + payout READY로 전이하고 confirmedAt을 기록한다")
    void approve_fromPending() {
        Settlement settlement = pendingSettlement();
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 6, 24, 9, 0);

        settlement.approve(confirmedAt);

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.APPROVED);
        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(settlement.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
    }

    @Test
    @DisplayName("승인: PENDING_APPROVAL이 아니면 예외를 던진다")
    void approve_whenNotPending_throws() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);

        assertThatThrownBy(() -> settlement.approve(LocalDateTime.of(2026, 6, 24, 9, 0)))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류: PENDING_APPROVAL 정산을 보류하면 SETTLEMENT_ON_HOLD로 전이한다")
    void hold_fromPending() {
        Settlement settlement = pendingSettlement();

        settlement.hold();

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLEMENT_ON_HOLD);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
    }

    @Test
    @DisplayName("승인 보류: PENDING_APPROVAL이 아니면 예외를 던진다")
    void hold_whenNotPending_throws() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);

        assertThatThrownBy(settlement::hold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류 해제: SETTLEMENT_ON_HOLD 정산을 해제하면 PENDING_APPROVAL로 전이한다")
    void releaseHold_fromOnHold() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.SETTLEMENT_ON_HOLD);

        settlement.releaseHold();

        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
    }

    @Test
    @DisplayName("승인 보류 해제: SETTLEMENT_ON_HOLD가 아니면 예외를 던진다")
    void releaseHold_whenNotOnHold_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::releaseHold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급: APPROVED & READY 정산을 지급하면 payout PAID로 전이하고 paidAt을 기록한다")
    void payout_fromApprovedReady() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.READY);
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 24, 15, 0);

        settlement.payout(paidAt);

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(settlement.getPaidAt()).isEqualTo(paidAt);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
    }

    @Test
    @DisplayName("지급: APPROVED & READY가 아니면 예외를 던진다")
    void payout_whenNotApprovedReady_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(() -> settlement.payout(LocalDateTime.of(2026, 6, 24, 15, 0)))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급 보류: APPROVED & READY 정산을 보류하면 payout PAYOUT_ON_HOLD로 전이한다")
    void payoutHold_fromApprovedReady() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.READY);

        settlement.payoutHold();

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAYOUT_ON_HOLD);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
    }

    @Test
    @DisplayName("지급 보류: APPROVED & READY가 아니면 예외를 던진다")
    void payoutHold_whenNotApprovedReady_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::payoutHold).isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("지급 보류 해제: APPROVED & PAYOUT_ON_HOLD 정산을 해제하면 payout READY로 전이한다")
    void releasePayoutHold_fromApprovedOnHold() {
        Settlement settlement = pendingSettlement();
        ReflectionTestUtils.setField(settlement, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(settlement, "payoutStatus", PayoutStatus.PAYOUT_ON_HOLD);

        settlement.releasePayoutHold();

        assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
    }

    @Test
    @DisplayName("지급 보류 해제: APPROVED & PAYOUT_ON_HOLD가 아니면 예외를 던진다")
    void releasePayoutHold_whenNotApprovedOnHold_throws() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(settlement::releasePayoutHold).isInstanceOf(SettlementInvalidStateException.class);
    }
}
