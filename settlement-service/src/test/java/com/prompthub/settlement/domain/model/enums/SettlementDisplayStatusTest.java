package com.prompthub.settlement.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDisplayStatusTest {

    @Test
    @DisplayName("승인 전 단계는 settlementStatus로 표시 상태가 결정된다 (지급 축 무관)")
    void from_preApproval_derivedFromSettlementStatus() {
        assertThat(SettlementDisplayStatus.from(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY))
                .isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY))
                .isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY))
                .isEqualTo(SettlementDisplayStatus.CANCELLED);
    }

    @Test
    @DisplayName("APPROVED는 payoutStatus에 따라 승인/지급신청/지급보류/지급완료로 갈린다")
    void from_approved_derivedFromPayoutStatus() {
        assertThat(SettlementDisplayStatus.from(SettlementStatus.APPROVED, PayoutStatus.NOT_READY))
                .isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.APPROVED, PayoutStatus.READY))
                .isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED))
                .isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD))
                .isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
        assertThat(SettlementDisplayStatus.from(SettlementStatus.APPROVED, PayoutStatus.PAID))
                .isEqualTo(SettlementDisplayStatus.PAID);
    }

    @Test
    @DisplayName("toCard는 7종 표시 상태를 요약 4카드로 접고 취소는 카드가 없다")
    void toCard_foldsIntoFourCardsAndExcludesCancelled() {
        assertThat(SettlementDisplayStatus.WAITING.toCard()).isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(SettlementDisplayStatus.APPROVAL_ON_HOLD.toCard()).isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(SettlementDisplayStatus.APPROVED.toCard()).isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(SettlementDisplayStatus.PAYOUT_REQUESTED.toCard()).isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(SettlementDisplayStatus.PAYOUT_ON_HOLD.toCard()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
        assertThat(SettlementDisplayStatus.PAID.toCard()).isEqualTo(SettlementDisplayStatus.PAID);
        assertThat(SettlementDisplayStatus.CANCELLED.toCard()).isNull();
    }
}
