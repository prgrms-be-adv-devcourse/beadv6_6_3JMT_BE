package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.PaymentRefundResult;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefundWithAllProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RefundResultContextLoaderTest {

	private static final UUID REFUND_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 13, 12, 0);

	@Mock private OrderRefundRepository refundRepository;
	@Mock private OrderRepository orderRepository;
	@InjectMocks private RefundResultContextLoader loader;

	private Order order;
	private OrderRefund refund;
	private PaymentRefundResult result;

	@BeforeEach
	void setUp() {
		order = createPaidOrderWithProducts();
		refund = createRequestedRefundWithAllProducts(order, REFUND_ID, REQUESTED_AT);
		result = new TestPaymentRefundResult(REFUND_ID, PAYMENT_ID, order.getId(), TOTAL_AMOUNT);
	}

	@Test
	void loadValidatedRefund_matchingResult_returnsLockedRefund() {
		given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

		assertThat(loader.loadValidatedRefund(result)).isSameAs(refund);
	}

	@Test
	void loadValidatedRefund_missingRefund_throwsConflict() {
		given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> loader.loadValidatedRefund(result))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_REQUEST_CONFLICT);
	}

	@Test
	void loadValidatedRefund_mismatchedAmount_throwsEventMismatch() {
		PaymentRefundResult mismatched = new TestPaymentRefundResult(
			REFUND_ID,
			PAYMENT_ID,
			order.getId(),
			TOTAL_AMOUNT - 1
		);
		given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

		assertThatThrownBy(() -> loader.loadValidatedRefund(mismatched))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_EVENT_MISMATCH);
	}

	@Test
	void loadOrderForUpdate_existingOrder_returnsLockedOrder() {
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));

		assertThat(loader.loadOrderForUpdate(order.getId())).isSameAs(order);
	}

	@Test
	void loadOrderForUpdate_missingOrder_throwsNotFound() {
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.empty());

		assertThatThrownBy(() -> loader.loadOrderForUpdate(order.getId()))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
	}

	private record TestPaymentRefundResult(
		UUID refundId,
		UUID paymentId,
		UUID orderId,
		int totalRefundAmount
	) implements PaymentRefundResult {
	}
}
