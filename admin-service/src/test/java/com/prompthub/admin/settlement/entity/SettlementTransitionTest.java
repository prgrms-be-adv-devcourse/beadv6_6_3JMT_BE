package com.prompthub.admin.settlement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.admin.settlement.exception.SettlementAlreadyCancelledException;
import com.prompthub.admin.settlement.exception.SettlementAlreadyPaidException;
import com.prompthub.admin.settlement.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * seller_settlement(운영 단일 진실) 재매핑 후 단일 status(SettlementDisplayStatus 7값) 전이 규칙 검증.
 * 전이 가드는 user-service SellerSettlement 엔티티(소유자)와 동일해야 한다.
 */
class SettlementTransitionTest {

	private Settlement settlement(SettlementDisplayStatus status) {
		try {
			Constructor<Settlement> constructor = Settlement.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			Settlement settlement = constructor.newInstance();
			ReflectionTestUtils.setField(settlement, "status", status);
			return settlement;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("승인: WAITING이면 APPROVED로 전이하고 approvedAt을 기록한다")
	void approve_waiting_toApproved() {
		Settlement settlement = settlement(SettlementDisplayStatus.WAITING);
		LocalDateTime approvedAt = LocalDateTime.of(2026, 6, 24, 9, 0);

		settlement.approve(approvedAt);

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
		assertThat(settlement.getApprovedAt()).isEqualTo(approvedAt);
	}

	@Test
	@DisplayName("승인: WAITING이 아니면 예외를 던진다")
	void approve_notWaiting_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVED);

		assertThatThrownBy(() -> settlement.approve(LocalDateTime.of(2026, 6, 24, 9, 0)))
			.isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("승인보류: WAITING이면 APPROVAL_ON_HOLD로 전이한다")
	void hold_waiting_toApprovalOnHold() {
		Settlement settlement = settlement(SettlementDisplayStatus.WAITING);

		settlement.hold();

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
	}

	@Test
	@DisplayName("승인보류: WAITING이 아니면 예외를 던진다")
	void hold_notWaiting_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVED);

		assertThatThrownBy(settlement::hold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("승인보류해제: APPROVAL_ON_HOLD이면 WAITING으로 복귀한다")
	void releaseHold_onHold_toWaiting() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVAL_ON_HOLD);

		settlement.releaseHold();

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
	}

	@Test
	@DisplayName("승인보류해제: APPROVAL_ON_HOLD이 아니면 예외를 던진다")
	void releaseHold_notOnHold_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.WAITING);

		assertThatThrownBy(settlement::releaseHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("지급: PAYOUT_REQUESTED이면 PAID로 전이하고 paidAt을 기록한다")
	void payout_requested_toPaid() {
		Settlement settlement = settlement(SettlementDisplayStatus.PAYOUT_REQUESTED);
		LocalDateTime paidAt = LocalDateTime.of(2026, 6, 24, 15, 0);

		settlement.payout(paidAt);

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
		assertThat(settlement.getPaidAt()).isEqualTo(paidAt);
	}

	@Test
	@DisplayName("지급: APPROVED(지급신청 전)에서는 지급할 수 없다")
	void payout_approved_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVED);

		assertThatThrownBy(() -> settlement.payout(LocalDateTime.of(2026, 6, 24, 15, 0)))
			.isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("지급보류: PAYOUT_REQUESTED이면 PAYOUT_ON_HOLD로 전이한다")
	void payoutHold_requested_toPayoutOnHold() {
		Settlement settlement = settlement(SettlementDisplayStatus.PAYOUT_REQUESTED);

		settlement.payoutHold();

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_ON_HOLD);
	}

	@Test
	@DisplayName("지급보류: PAYOUT_REQUESTED이 아니면 예외를 던진다")
	void payoutHold_notRequested_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVED);

		assertThatThrownBy(settlement::payoutHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("지급보류해제: PAYOUT_ON_HOLD이면 PAYOUT_REQUESTED로 복귀한다")
	void releasePayoutHold_onHold_toRequested() {
		Settlement settlement = settlement(SettlementDisplayStatus.PAYOUT_ON_HOLD);

		settlement.releasePayoutHold();

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
	}

	@Test
	@DisplayName("지급보류해제: PAYOUT_ON_HOLD이 아니면 예외를 던진다")
	void releasePayoutHold_notOnHold_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.APPROVED);

		assertThatThrownBy(settlement::releasePayoutHold).isInstanceOf(SettlementInvalidStateException.class);
	}

	@Test
	@DisplayName("취소: PAID/CANCELLED가 아니면 CANCELLED로 전이하고 cancelledAt을 기록한다")
	void cancel_active_toCancelled() {
		Settlement settlement = settlement(SettlementDisplayStatus.WAITING);
		LocalDateTime cancelledAt = LocalDateTime.of(2026, 6, 24, 9, 0);

		settlement.cancel(cancelledAt);

		assertThat(settlement.displayStatus()).isEqualTo(SettlementDisplayStatus.CANCELLED);
		assertThat(settlement.getCancelledAt()).isEqualTo(cancelledAt);
	}

	@Test
	@DisplayName("취소: 이미 지급완료(PAID)된 정산은 취소할 수 없다")
	void cancel_paid_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.PAID);

		assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 9, 0)))
			.isInstanceOf(SettlementAlreadyPaidException.class);
	}

	@Test
	@DisplayName("취소: 이미 취소된 정산은 다시 취소할 수 없다")
	void cancel_cancelled_throws() {
		Settlement settlement = settlement(SettlementDisplayStatus.CANCELLED);

		assertThatThrownBy(() -> settlement.cancel(LocalDateTime.of(2026, 6, 24, 10, 0)))
			.isInstanceOf(SettlementAlreadyCancelledException.class);
	}
}
