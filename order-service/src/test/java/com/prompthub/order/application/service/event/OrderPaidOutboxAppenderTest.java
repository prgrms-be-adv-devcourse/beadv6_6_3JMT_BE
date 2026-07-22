package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
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
class OrderPaidOutboxAppenderTest {

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@InjectMocks
	private OrderPaidOutboxAppender orderPaidOutboxAppender;

	@Test
	void append_freeOrder_buildsCurrentOrderPaidPayloadAndStoresMessage() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, 0);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, 0));
		order.completeFreeOrder();
		EventMessage<OrderPaidPayload> message = new EventMessage<>(
			UUID.randomUUID(), "ORDER_PAID", LocalDateTime.now(), "ORDER", order.getId(), OrderPaidPayload.from(order)
		);
		given(orderEventMessageFactory.createOrderPaidMessage(eq(order.getId()), any())).willReturn(message);

		orderPaidOutboxAppender.append(order);

		ArgumentCaptor<OrderPaidPayload> payloadCaptor = ArgumentCaptor.forClass(OrderPaidPayload.class);
		then(orderEventMessageFactory).should().createOrderPaidMessage(eq(order.getId()), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue().totalOrderAmount()).isZero();
		assertThat(payloadCaptor.getValue().products()).singleElement()
			.satisfies(product -> assertThat(product.productId()).isEqualTo(PRODUCT_ID_1));
		then(outboxEventAppender).should().append(message);
	}
}
