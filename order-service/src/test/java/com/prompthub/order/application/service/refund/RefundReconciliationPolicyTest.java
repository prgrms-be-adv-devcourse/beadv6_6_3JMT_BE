package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefund;
import static org.assertj.core.api.Assertions.assertThat;

class RefundReconciliationPolicyTest {

	private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 13, 12, 0);
	private final RefundReconciliationPolicy policy = new RefundReconciliationPolicy();

	@Test
	void decide_unresolvedAttempts_usesTwoFiveTenMinuteIntervalsThenTimeout() {
		OrderRefund refund = refund();
		LocalDateTime firstCheck = REQUESTED_AT.plusSeconds(65);

		assertThat(policy.decide(refund, firstCheck).nextCheckAt()).isEqualTo(firstCheck.plusMinutes(2));
		refund.recordReconciliationAttempt(firstCheck.plusMinutes(2));
		LocalDateTime secondCheck = firstCheck.plusMinutes(2);
		assertThat(policy.decide(refund, secondCheck).nextCheckAt()).isEqualTo(secondCheck.plusMinutes(5));
		refund.recordReconciliationAttempt(secondCheck.plusMinutes(5));
		LocalDateTime thirdCheck = secondCheck.plusMinutes(5);
		assertThat(policy.decide(refund, thirdCheck).nextCheckAt()).isEqualTo(thirdCheck.plusMinutes(10));
		refund.recordReconciliationAttempt(thirdCheck.plusMinutes(10));

		assertThat(policy.decide(refund, thirdCheck.plusMinutes(10)).action())
			.isEqualTo(RefundReconciliationPolicy.Action.TIMEOUT);
	}

	private OrderRefund refund() {
		Order order = createPaidOrderWithProducts();
		return createRequestedRefund(order, UUID.randomUUID(), REQUESTED_AT);
	}
}
