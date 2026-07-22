package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
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
	void validateApproved_convertsOffsetTimestampToKoreanLocalDateTime() {
		LocalDateTime approvedAt = validator.validate(new PaymentApprovedPayload(
			ORDER_A, "2026-07-17T01:00:05Z"
		));

		assertThat(approvedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefunded_acceptsCurrentPaymentContract() {
		LocalDateTime refundedAt = validator.validate(new PaymentRefundedPayload(
			ORDER_A, 10_000, "2026-07-17T01:00:05Z"
		));

		assertThat(refundedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefundFailed_acceptsCurrentPaymentContract() {
		LocalDateTime failedAt = validator.validate(new PaymentRefundedEventHandler.RefundFailedPayload(
			ORDER_A, 10_000, "2026-07-17T01:00:05Z"
		));

		assertThat(failedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateRefundEvents_rejectInvalidValues() {
		assertInvalid(() -> validator.validate(new PaymentRefundedPayload(null, 10_000, "2026-07-17T01:00:05Z")));
		assertInvalid(() -> validator.validate(new PaymentRefundedPayload(ORDER_A, 0, "2026-07-17T01:00:05Z")));
		assertInvalid(() -> validator.validate(new PaymentRefundedPayload(ORDER_A, 10_000, "invalid")));
		assertInvalid(() -> validator.validate(new PaymentRefundedEventHandler.RefundFailedPayload(null, 10_000, "2026-07-17T01:00:05Z")));
		assertInvalid(() -> validator.validate(new PaymentRefundedEventHandler.RefundFailedPayload(ORDER_A, -1, "2026-07-17T01:00:05Z")));
		assertInvalid(() -> validator.validate(new PaymentRefundedEventHandler.RefundFailedPayload(ORDER_A, 10_000, " ")));
	}

	@Test
	void validateFailed_acceptsSingleOrderPayload() {
		validator.validate(new PaymentFailedPayload(PAYMENT_ID, ORDER_A, BUYER_ID));
	}

	@Test
	void validateFailed_acceptsReducedPaymentContract() {
		validator.validate(new PaymentFailedPayload(
			null,
			ORDER_A,
			null,
			30_000,
			null,
			null,
			"2026-07-17T01:00:05Z"
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
