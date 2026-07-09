package com.prompthub.admin.settlement.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDisplayStatusTest {

	@Test
	@DisplayName("대기·승인보류는 요약 카드에서 WAITING 버킷으로 묶인다")
	void toCard_waitingBucket() {
		assertThat(SettlementDisplayStatus.WAITING.toCard()).isEqualTo(SettlementDisplayStatus.WAITING);
		assertThat(SettlementDisplayStatus.APPROVAL_ON_HOLD.toCard()).isEqualTo(SettlementDisplayStatus.WAITING);
	}

	@Test
	@DisplayName("승인·지급신청은 요약 카드에서 APPROVED 버킷으로 묶인다")
	void toCard_approvedBucket() {
		assertThat(SettlementDisplayStatus.APPROVED.toCard()).isEqualTo(SettlementDisplayStatus.APPROVED);
		assertThat(SettlementDisplayStatus.PAYOUT_REQUESTED.toCard()).isEqualTo(SettlementDisplayStatus.APPROVED);
	}

	@Test
	@DisplayName("지급보류·지급완료는 각자 자기 카드로 묶인다")
	void toCard_ownBucket() {
		assertThat(SettlementDisplayStatus.PAYOUT_ON_HOLD.toCard()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
		assertThat(SettlementDisplayStatus.PAID.toCard()).isEqualTo(SettlementDisplayStatus.PAID);
	}

	@Test
	@DisplayName("취소는 요약 카드 집계 대상이 아니다(null)")
	void toCard_cancelledExcluded() {
		assertThat(SettlementDisplayStatus.CANCELLED.toCard()).isNull();
	}
}
