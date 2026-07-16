package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrders;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventValidatorTest {

	private final PaymentEventValidator validator = new PaymentEventValidator();

	@Test
	void validateFailed_returnsSortedOrderIds() {
		assertThat(validator.validate(failedPayload())).containsExactly(ORDER_A, ORDER_B);
	}

	@Test
	void validateFailed_rejectsDuplicateOrderIds() {
		PaymentFailedPayload payload = new PaymentFailedPayload(
			PAYMENT_ID,
			List.of(ORDER_A, ORDER_A),
			"PAY_FAILED",
			"PG 결제 실패",
			FAILED_AT
		);

		assertThatThrownBy(() -> validator.validate(payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	void validateEnvelope_rejectsMissingOccurredAt() {
		assertThatThrownBy(() -> validator.validateEnvelope(UUID.randomUUID(), "PAYMENT_FAILED", null))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	void validateApproved_rejectsDuplicateOrderProductIds() {
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			BUYER_ID,
			30_000,
			List.of(
				new PaymentApprovedOrderPayload(ORDER_A, List.of(ORDER_PRODUCT_A)),
				new PaymentApprovedOrderPayload(ORDER_B, List.of(ORDER_PRODUCT_A))
			),
			APPROVED_AT
		);

		assertThatThrownBy(() -> validator.validate(payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	void validateApproved_returnsSortedOrderIds() {
		assertThat(validator.validate(approvedPayload(createdOrders())))
			.containsExactly(ORDER_A, ORDER_B);
	}
}
