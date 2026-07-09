package com.prompthub.admin.settlement.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SettlementDisplayStatusTest {

	@Test
	void 승인대기는_WAITING_으로_표시한다() {
		assertThat(SettlementDisplayStatus.from(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY))
			.isEqualTo(SettlementDisplayStatus.WAITING);
	}

	@Test
	void 승인완료는_지급상태에_따라_갈린다() {
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
	void 보류와_취소는_지급상태와_무관하다() {
		assertThat(SettlementDisplayStatus.from(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY))
			.isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
		assertThat(SettlementDisplayStatus.from(SettlementStatus.CANCELLED, PayoutStatus.PAID))
			.isEqualTo(SettlementDisplayStatus.CANCELLED);
	}
}
