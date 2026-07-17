package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
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
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			30_000,
			"2026-07-17T01:00:05Z"
		);

		LocalDateTime approvedAt = validator.validate(payload);

		assertThat(approvedAt).isEqualTo(LocalDateTime.of(2026, 7, 17, 10, 0, 5));
	}

	@Test
	void validateApproved_rejectsMissingIdentifiers() {
		assertInvalidApproved(new PaymentApprovedPayload(null, ORDER_A, BUYER_ID, 30_000, validApprovedAt()));
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, null, BUYER_ID, 30_000, validApprovedAt()));
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, ORDER_A, null, 30_000, validApprovedAt()));
	}

	@Test
	void validateApproved_rejectsNonPositiveAmount() {
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, ORDER_A, BUYER_ID, 0, validApprovedAt()));
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, ORDER_A, BUYER_ID, -1, validApprovedAt()));
	}

	@Test
	void validateApproved_rejectsBlankApprovedAt() {
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, ORDER_A, BUYER_ID, 30_000, null));
		assertInvalidApproved(new PaymentApprovedPayload(PAYMENT_ID, ORDER_A, BUYER_ID, 30_000, "  "));
	}

	@Test
	void validateApproved_rejectsTimestampWithoutOffset() {
		assertInvalidApproved(new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			30_000,
			"2026-07-17T10:00:05"
		));
	}

	@Test
	void validateApproved_rejectsInvalidTimestamp() {
		assertInvalidApproved(new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			30_000,
			"2026-07-32T10:00:05+09:00"
		));
	}

	@Test
	void validateFailed_acceptsSingleOrderPayload() {
		validator.validate(new PaymentFailedPayload(PAYMENT_ID, ORDER_A, BUYER_ID));
	}

	@Test
	void validateFailed_rejectsMissingIdentifiers() {
		assertInvalidFailed(new PaymentFailedPayload(null, ORDER_A, BUYER_ID));
		assertInvalidFailed(new PaymentFailedPayload(PAYMENT_ID, null, BUYER_ID));
		assertInvalidFailed(new PaymentFailedPayload(PAYMENT_ID, ORDER_A, null));
	}

	@Test
	void validateEnvelope_rejectsMissingOccurredAt() {
		assertThatThrownBy(() -> validator.validateEnvelope(UUID.randomUUID(), "PAYMENT_FAILED", null))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	private void assertInvalidApproved(PaymentApprovedPayload payload) {
		assertThatThrownBy(() -> validator.validate(payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	private void assertInvalidFailed(PaymentFailedPayload payload) {
		assertThatThrownBy(() -> validator.validate(payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	private String validApprovedAt() {
		return "2026-07-17T10:00:05+09:00";
	}
}
