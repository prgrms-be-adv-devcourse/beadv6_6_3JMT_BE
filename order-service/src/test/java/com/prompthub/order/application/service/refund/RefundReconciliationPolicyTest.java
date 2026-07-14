package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.OrderRefund;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.paidProduct;
import static org.assertj.core.api.Assertions.assertThat;

class RefundReconciliationPolicyTest {

	private final RefundReconciliationPolicy policy = new RefundReconciliationPolicy();

	@Test
	void decide_followsApprovedSchedule() {
		OrderRefund refund = refund();
		LocalDateTime checkedAt = REQUESTED_AT.plusMinutes(2);

		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.RESCHEDULE, 1, REQUESTED_AT.plusMinutes(5));
		refund.scheduleNext(1, REQUESTED_AT.plusMinutes(5));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.RESCHEDULE, 2, REQUESTED_AT.plusMinutes(10));
		refund.scheduleNext(2, REQUESTED_AT.plusMinutes(10));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.RESCHEDULE, 3, REQUESTED_AT.plusMinutes(20));
		refund.scheduleNext(3, REQUESTED_AT.plusMinutes(20));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.MARK_UNKNOWN, 4, checkedAt.plusMinutes(30));
		refund.scheduleNext(4, checkedAt.plusMinutes(30));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.RESCHEDULE, 5, checkedAt.plusHours(1));
		refund.scheduleNext(5, checkedAt.plusHours(1));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.RESCHEDULE, 6, checkedAt.plusHours(3));
		refund.scheduleNext(6, checkedAt.plusHours(3));
		assertDecision(refund, checkedAt, RefundReconciliationPolicy.Action.MANUAL_REVIEW, 6, null);
	}

	private void assertDecision(OrderRefund refund, LocalDateTime checkedAt,
		RefundReconciliationPolicy.Action action, int attempt, LocalDateTime nextCheckAt) {
		RefundReconciliationPolicy.Decision decision = policy.decide(refund, checkedAt);
		assertThat(decision.action()).isEqualTo(action);
		assertThat(decision.attempt()).isEqualTo(attempt);
		assertThat(decision.nextCheckAt()).isEqualTo(nextCheckAt);
	}

	private OrderRefund refund() {
		return OrderRefund.request(ORDER_ID, PAYMENT_ID, BUYER_ID,
			List.of(paidProduct(ORDER_PRODUCT_ID_1, 10_000)), REQUESTED_AT);
	}
}
