package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.RefundResult;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.OrderEventMessageFactory.RefundRequestedPayload;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;
	@Mock
	private OutboxEventAppender outboxEventAppender;

	private OrderRefundService service;

	@BeforeEach
	void setUp() {
		service = new OrderRefundService(
			orderRepository,
			orderEventMessageFactory,
			outboxEventAppender,
			Clock.fixed(Instant.parse("2026-07-21T03:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
	}

	@Test
	void requestRefund_calculatesAmountAndAppendsCurrentPaymentContract() {
		Order order = createPaidOrderWithProducts();
		List<UUID> productIds = order.getOrderProducts().stream().map(OrderProduct::getId).toList();
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
		given(orderEventMessageFactory.createOrderRefundRequestedMessage(any(), any()))
			.willAnswer(invocation -> new EventMessage<>(
				UUID.randomUUID(),
				"ORDER_REFUND_REQUESTED",
				invocation.<RefundRequestedPayload>getArgument(1).requestedAt(),
				"ORDER",
				order.getId(),
				invocation.getArgument(1)
			));

		RefundResult result = service.requestRefund(BUYER_ID, order.getId(), productIds);

		assertThat(result.refundAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
		assertThat(result.status()).isEqualTo("REQUESTED");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.REFUND_REQUESTED);
		ArgumentCaptor<RefundRequestedPayload> payloadCaptor =
			ArgumentCaptor.forClass(RefundRequestedPayload.class);
		then(orderEventMessageFactory).should()
			.createOrderRefundRequestedMessage(org.mockito.ArgumentMatchers.eq(order.getId()), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue().refundRequestId()).isEqualTo(result.refundRequestId());
		assertThat(payloadCaptor.getValue().refundAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
		then(outboxEventAppender).should().append(any());
	}
}
