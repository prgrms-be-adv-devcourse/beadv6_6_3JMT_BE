package com.prompthub.order.application.service.event;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentRefundedProcessorTest {

	@Mock
	private ProcessedEventService processedEventService;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderOutboxAppender orderOutboxAppender;
	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();
	@InjectMocks
	private PaymentRefundedProcessor processor;

	@Test
	void process_completesRequestedProductsAndPublishesProductList() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		order.requestRefund(List.of(target.getId()));
		UUID eventId = UUID.randomUUID();
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
		processor.process(
			eventId,
			"PAYMENT_REFUNDED",
			REFUNDED_AT,
			new PaymentRefundedPayload(order.getId(), PRODUCT_AMOUNT_1, "2026-06-19T12:20:00+09:00")
		);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(target.getOrderStatus()).isEqualTo(OrderProductStatus.REFUNDED);
		ArgumentCaptor<List<OrderProduct>> productsCaptor = ArgumentCaptor.forClass(List.class);
		then(orderOutboxAppender).should().appendRefunded(eq(order), productsCaptor.capture(), eq(REFUNDED_AT));
		assertThat(productsCaptor.getValue()).singleElement()
			.satisfies(product -> assertThat(product.getId()).isEqualTo(target.getId()));
		then(processedEventService).should().markProcessed(eventId, "order-service", "PAYMENT_REFUNDED", REFUNDED_AT);
	}

	@Test
	void processFailed_keepsOrderAndProductRequested() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		order.requestRefund(List.of(target.getId()));
		UUID eventId = UUID.randomUUID();
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));

		processor.processFailed(
			eventId,
			"PAYMENT_REFUND_FAILED",
			REFUNDED_AT,
			new PaymentRefundedEventHandler.RefundFailedPayload(
				order.getId(),
				PRODUCT_AMOUNT_1,
				"2026-06-19T12:20:00+09:00"
			)
		);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(target.getOrderStatus()).isEqualTo(OrderProductStatus.REFUND_REQUESTED);
		then(orderOutboxAppender).should().appendRefundFailed(order, PRODUCT_AMOUNT_1, REFUNDED_AT);
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_REFUND_FAILED", REFUNDED_AT);
	}
}
