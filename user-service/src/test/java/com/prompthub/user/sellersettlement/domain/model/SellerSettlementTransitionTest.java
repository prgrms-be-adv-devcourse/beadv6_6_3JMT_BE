package com.prompthub.user.sellersettlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementInvalidStateException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerSettlementTransitionTest {

    private SellerSettlement waiting() {
        return SellerSettlement.seed(
                UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), new BigDecimal("0.00"), LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    private SellerSettlement approved() {
        SellerSettlement s = waiting();
        s.approve();
        return s;
    }

    private SellerSettlement payoutRequested() {
        SellerSettlement s = approved();
        s.requestPayout();
        return s;
    }

    @Test
    void approve_WAITING에서_APPROVED로() {
        SellerSettlement s = waiting();
        s.approve();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(s.getApprovedAt()).isNotNull();
    }

    @Test
    void approve_WAITING이_아니면_예외() {
        assertThatThrownBy(() -> approved().approve())
                .isInstanceOf(SellerSettlementInvalidStateException.class);
    }

    @Test
    void hold_WAITING에서_APPROVAL_ON_HOLD로() {
        SellerSettlement s = waiting();
        s.hold();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
    }

    @Test
    void releaseHold_APPROVAL_ON_HOLD에서_WAITING으로() {
        SellerSettlement s = waiting();
        s.hold();
        s.releaseHold();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
    }

    @Test
    void requestPayout_APPROVED에서_PAYOUT_REQUESTED로() {
        SellerSettlement s = approved();
        s.requestPayout();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
        assertThat(s.getPayoutRequestedAt()).isNotNull();
    }

    @Test
    void requestPayout_APPROVED가_아니면_예외() {
        assertThatThrownBy(() -> waiting().requestPayout())
                .isInstanceOf(SellerSettlementInvalidStateException.class);
    }

    @Test
    void payout_PAYOUT_REQUESTED에서_PAID로() {
        SellerSettlement s = payoutRequested();
        s.payout();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.PAID);
        assertThat(s.getPaidAt()).isNotNull();
    }

    @Test
    void payoutHold_PAYOUT_REQUESTED에서_PAYOUT_ON_HOLD로() {
        SellerSettlement s = payoutRequested();
        s.payoutHold();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
    }

    @Test
    void releasePayoutHold_PAYOUT_ON_HOLD에서_PAYOUT_REQUESTED로() {
        SellerSettlement s = payoutRequested();
        s.payoutHold();
        s.releasePayoutHold();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
    }

    @Test
    void cancel_지급완료가_아니면_CANCELLED로() {
        SellerSettlement s = approved();
        s.cancel();
        assertThat(s.getStatus()).isEqualTo(SettlementDisplayStatus.CANCELLED);
        assertThat(s.getCancelledAt()).isNotNull();
    }

    @Test
    void cancel_PAID면_예외() {
        SellerSettlement s = payoutRequested();
        s.payout();
        assertThatThrownBy(s::cancel)
                .isInstanceOf(SellerSettlementInvalidStateException.class);
    }

    @Test
    void canRequestPayout_APPROVED일때만_true() {
        assertThat(approved().canRequestPayout()).isTrue();
        assertThat(waiting().canRequestPayout()).isFalse();
    }
}
