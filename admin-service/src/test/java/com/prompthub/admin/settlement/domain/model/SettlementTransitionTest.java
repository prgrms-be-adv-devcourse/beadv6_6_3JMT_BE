package com.prompthub.admin.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.admin.settlement.domain.exception.SettlementAlreadyPaidException;
import com.prompthub.admin.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementTransitionTest {

	private Settlement settlement(SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
		try {
			Constructor<Settlement> constructor = Settlement.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			Settlement settlement = constructor.newInstance();
			ReflectionTestUtils.setField(settlement, "settlementStatus", settlementStatus);
			ReflectionTestUtils.setField(settlement, "payoutStatus", payoutStatus);
			return settlement;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void 승인_PENDING_APPROVAL_이면_APPROVED_READY_로_전이하고_confirmedAt_을_기록한다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
		LocalDateTime confirmedAt = LocalDateTime.of(2026, 6, 24, 9, 0);

		settlement.approve(confirmedAt);

		assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.APPROVED);
		assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.READY);
		assertThat(settlement.getConfirmedAt()).isEqualTo(confirmedAt);
		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
	}

	@Test
	void 승인_PENDING_APPROVAL_이_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.NOT_READY);

		assertThatThrownBy(() -> settlement.approve(LocalDateTime.of(2026, 6, 24, 9, 0)))
			.isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 승인보류_PENDING_APPROVAL_이면_SETTLEMENT_ON_HOLD_로_전이한다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);

		settlement.hold();

		assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLEMENT_ON_HOLD);
	}

	@Test
	void 승인보류_PENDING_APPROVAL_이_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.NOT_READY);

		assertThatThrownBy(settlement::hold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 승인보류해제_SETTLEMENT_ON_HOLD_이면_PENDING_APPROVAL_로_전이한다() {
		Settlement settlement = settlement(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY);

		settlement.releaseHold();

		assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
	}

	@Test
	void 승인보류해제_SETTLEMENT_ON_HOLD_가_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);

		assertThatThrownBy(settlement::releaseHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 지급_APPROVED_PAYOUT_REQUESTED_이면_PAID_로_전이하고_paidAt_을_기록한다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED);
		LocalDateTime paidAt = LocalDateTime.of(2026, 6, 24, 15, 0);

		settlement.payout(paidAt);

		assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAID);
		assertThat(settlement.getPaidAt()).isEqualTo(paidAt);
	}

	@Test
	void 지급_APPROVED_READY_상태에서는_지급할_수_없다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.READY);

		assertThatThrownBy(() -> settlement.payout(LocalDateTime.of(2026, 6, 24, 15, 0)))
			.isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 지급_APPROVED_PAYOUT_REQUESTED_가_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);

		assertThatThrownBy(() -> settlement.payout(LocalDateTime.of(2026, 6, 24, 15, 0)))
			.isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 지급보류_APPROVED_PAYOUT_REQUESTED_이면_PAYOUT_ON_HOLD_로_전이한다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED);

		settlement.payoutHold();

		assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAYOUT_ON_HOLD);
	}

	@Test
	void 지급보류_APPROVED_PAYOUT_REQUESTED_가_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);

		assertThatThrownBy(settlement::payoutHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 지급보류해제_APPROVED_PAYOUT_ON_HOLD_이면_PAYOUT_REQUESTED_로_복귀한다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD);

		settlement.releasePayoutHold();

		assertThat(settlement.getPayoutStatus()).isEqualTo(PayoutStatus.PAYOUT_REQUESTED);
	}

	@Test
	void 지급보류해제_APPROVED_PAYOUT_ON_HOLD_가_아니면_예외를_던진다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);

		assertThatThrownBy(settlement::releasePayoutHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	void 취소_PENDING_APPROVAL_이면_CANCELLED_로_전이하고_canceledAt_을_기록한다() {
		Settlement settlement = settlement(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
		LocalDateTime canceledAt = LocalDateTime.of(2026, 6, 24, 9, 0);

		settlement.cancel(canceledAt);

		assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
		assertThat(settlement.getCanceledAt()).isEqualTo(canceledAt);
	}

	@Test
	void 취소_이미_지급완료_PAID_된_정산은_취소할_수_없다() {
		Settlement settlement = settlement(SettlementStatus.APPROVED, PayoutStatus.PAID);

		assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 9, 0)))
			.isInstanceOf(SettlementAlreadyPaidException.class);
	}

	@Test
	void 취소_이미_취소된_정산은_다시_취소할_수_없다() {
		Settlement settlement = settlement(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY);

		assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 10, 0)))
			.isInstanceOf(SettlementAlreadyCancelledException.class);
	}
}
