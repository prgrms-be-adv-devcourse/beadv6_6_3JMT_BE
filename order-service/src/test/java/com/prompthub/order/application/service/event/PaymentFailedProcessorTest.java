package com.prompthub.order.application.service.event;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_C;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentFailedProcessorTest {

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderRepository orderRepository;

	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();

	@InjectMocks
	private PaymentFailedProcessor processor;

	@Test
	void process_createdOrder_marksOrderAndFourProductsFailed() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_FAILED", FAILED_AT);
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = {"FAILED", "COMPLETED", "PARTIAL_REFUNDED", "ALL_REFUNDED"})
	void process_lateFailure_isNoOpExceptProcessedEvent(OrderStatus status) {
		Order order = createdOrder();
		if (status == OrderStatus.FAILED) {
			order.markFailed();
		} else {
			order.markPaid();
			if (status == OrderStatus.PARTIAL_REFUNDED) {
				order.refundOrderProduct(ORDER_PRODUCT_A, 10_000, FAILED_AT);
			} else if (status == OrderStatus.ALL_REFUNDED) {
				order.refundOrderProduct(ORDER_PRODUCT_A, 10_000, FAILED_AT);
				order.refundOrderProduct(ORDER_PRODUCT_B, 20_000, FAILED_AT);
				order.refundOrderProduct(ORDER_PRODUCT_C, 30_000, FAILED_AT);
				order.refundOrderProduct(ORDER_PRODUCT_D, 40_000, FAILED_AT);
			}
		}
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(order.getOrderStatus()).isEqualTo(status);
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_FAILED", FAILED_AT);
	}

	@Test
	void process_buyerMismatch_rejectsWithoutMutationOrProcessedEvent() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		PaymentFailedPayload payload = new PaymentFailedPayload(PAYMENT_ID, ORDER_A, OTHER_BUYER_ID);

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_missingOrder_throwsBeforeStateChange() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload()))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		then(orderRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutStateChange() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false, true);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	private void stubTarget(UUID eventId, Order order) {
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
	}
}
