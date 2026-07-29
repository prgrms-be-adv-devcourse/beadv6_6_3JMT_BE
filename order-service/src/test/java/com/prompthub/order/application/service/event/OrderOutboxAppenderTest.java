package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.application.dto.event.order.OrderExpiredPayload;
import com.prompthub.order.application.dto.event.order.OrderPaidPayload;
import com.prompthub.order.application.dto.event.order.OrderPaymentFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundFailedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_NUMBER;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderOutboxAppenderTest {

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@InjectMocks
	private OrderOutboxAppender orderOutboxAppender;

	@Test
	void append_freeOrder_buildsCurrentOrderPaidPayloadAndStoresMessage() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 0);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, 0));
		order.completeFreeOrder();
		EventMessage<OrderPaidPayload> message = new EventMessage<>(
			UUID.randomUUID(), "ORDER_PAID", LocalDateTime.now(), "ORDER", order.getId(), OrderPaidPayload.from(order)
		);
		given(orderEventMessageFactory.createOrderPaidMessage(eq(order.getId()), any())).willReturn(message);

		orderOutboxAppender.appendPaid(order);

		ArgumentCaptor<OrderPaidPayload> payloadCaptor = ArgumentCaptor.forClass(OrderPaidPayload.class);
		then(orderEventMessageFactory).should().createOrderPaidMessage(eq(order.getId()), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue().totalOrderAmount()).isZero();
		assertThat(payloadCaptor.getValue().products()).singleElement()
			.satisfies(product -> assertThat(product.productId()).isEqualTo(PRODUCT_ID_1));
		then(outboxEventAppender).should().append(message);
	}

	@Test
	void appendPaymentFailed_storesNotificationMessageWithOrderIdentityAndFailureTime() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 10_000);
		LocalDateTime failedAt = LocalDateTime.of(2026, 7, 27, 10, 0);
		OrderPaymentFailedPayload payload = new OrderPaymentFailedPayload(
			order.getId(), BUYER_ID, ORDER_NUMBER, "PAYMENT_DECLINED", "declined", failedAt
		);
		EventMessage<OrderPaymentFailedPayload> message = new EventMessage<>(
			UUID.randomUUID(), "ORDER_PAYMENT_FAILED", failedAt, "ORDER", order.getId(), payload
		);
		given(orderEventMessageFactory.createOrderPaymentFailedMessage(order.getId(), payload)).willReturn(message);

		orderOutboxAppender.appendPaymentFailed(order, "PAYMENT_DECLINED", "declined", failedAt);

		then(orderEventMessageFactory).should().createOrderPaymentFailedMessage(order.getId(), payload);
		then(outboxEventAppender).should().append(message);
	}

	@Test
	void appendExpired_storesNotificationMessageWithOrderIdentityAndExpirationTime() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 10_000);
		LocalDateTime expiredAt = LocalDateTime.of(2026, 7, 27, 10, 20);
		OrderExpiredPayload payload = new OrderExpiredPayload(order.getId(), BUYER_ID, ORDER_NUMBER, expiredAt);
		EventMessage<OrderExpiredPayload> message = new EventMessage<>(
			UUID.randomUUID(), "ORDER_EXPIRED", expiredAt, "ORDER", order.getId(), payload
		);
		given(orderEventMessageFactory.createOrderExpiredMessage(order.getId(), payload)).willReturn(message);

		orderOutboxAppender.appendExpired(order, expiredAt);

		then(orderEventMessageFactory).should().createOrderExpiredMessage(order.getId(), payload);
		then(outboxEventAppender).should().append(message);
	}

	@Test
	void appendRefundFailed_storesNotificationMessageWithOrderIdentityAmountAndFailureTime() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 10_000);
		LocalDateTime failedAt = LocalDateTime.of(2026, 7, 27, 10, 30);
		OrderRefundFailedPayload payload = new OrderRefundFailedPayload(
			order.getId(), BUYER_ID, ORDER_NUMBER, 10_000, failedAt
		);
		EventMessage<OrderRefundFailedPayload> message = new EventMessage<>(
			UUID.randomUUID(), "ORDER_REFUND_FAILED", failedAt, "ORDER", order.getId(), payload
		);
		given(orderEventMessageFactory.createOrderRefundFailedMessage(order.getId(), payload)).willReturn(message);

		orderOutboxAppender.appendRefundFailed(order, 10_000, failedAt);

		then(orderEventMessageFactory).should().createOrderRefundFailedMessage(order.getId(), payload);
		then(outboxEventAppender).should().append(message);
	}
}
