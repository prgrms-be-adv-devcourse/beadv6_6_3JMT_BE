package com.prompthub.order.application.service.event;

import com.prompthub.order.application.dto.event.PaymentApprovedCommand;
import com.prompthub.order.application.dto.event.PaymentFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundedCommand;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventValidatorTest {

	private final PaymentEventValidator validator = new PaymentEventValidator();

	@Test
	void validateApproved_acceptsRequiredAmountAndTimestamp() {
		LocalDateTime approvedAt = validator.validate(new PaymentApprovedCommand(
			ORDER_A, 100_000, LocalDateTime.of(2026, 7, 17, 10, 0, 5)
		));

		assertThat(approvedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefunded_acceptsCurrentPaymentContract() {
		LocalDateTime refundedAt = validator.validate(new PaymentRefundedCommand(
			ORDER_A, 10_000, LocalDateTime.of(2026, 7, 17, 10, 0, 5)
		));

		assertThat(refundedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefundFailed_acceptsCurrentPaymentContract() {
		LocalDateTime failedAt = validator.validate(new PaymentRefundFailedCommand(
			ORDER_A, 10_000, LocalDateTime.of(2026, 7, 17, 10, 0, 5)
		));

		assertThat(failedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefundEvents_rejectInvalidValues() {
		assertInvalid(() -> validator.validate(new PaymentRefundedCommand(null, 10_000, LocalDateTime.now())));
		assertInvalid(() -> validator.validate(new PaymentRefundedCommand(ORDER_A, 0, LocalDateTime.now())));
		assertInvalid(() -> validator.validate(new PaymentRefundedCommand(ORDER_A, 10_000, null)));
		assertInvalid(() -> validator.validate(new PaymentRefundFailedCommand(null, 10_000, LocalDateTime.now())));
		assertInvalid(() -> validator.validate(new PaymentRefundFailedCommand(ORDER_A, -1, LocalDateTime.now())));
		assertInvalid(() -> validator.validate(new PaymentRefundFailedCommand(ORDER_A, 10_000, null)));
	}

	@Test
	void validateFailed_acceptsSingleOrderPayload() {
		validator.validate(new PaymentFailedCommand(PAYMENT_ID, ORDER_A, BUYER_ID, 0, null, null, LocalDateTime.now()));
	}

	@Test
	void validateFailed_acceptsReducedPaymentContract() {
		validator.validate(new PaymentFailedCommand(
			null,
			ORDER_A,
			null,
			30_000,
			null,
			null,
			LocalDateTime.now()
		));
	}

	@Test
	void validateEnvelope_rejectsMissingOccurredAt() {
		assertInvalid(() -> validator.validateEnvelope(UUID.randomUUID(), "PAYMENT_FAILED", null));
	}

	private void assertInvalid(Runnable validation) {
		assertThatThrownBy(validation::run)
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}
}
