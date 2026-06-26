package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_ITEM_COUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaymentApprovedEvent;
import static com.prompthub.order.fixture.OrderFixture.createPaymentRefundedEvent;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxEventAppenderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	@DisplayName("ORDER_PAID payload를 JSON으로 직렬화해 OutboxEvent를 저장한다")
	void appendOrderPaid_savesSerializedOutboxEvent() throws Exception {
		Order order = createPendingOrderWithProducts();
		PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId());
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);

		appender.appendOrderPaid(order, event);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getAggregateId()).isEqualTo(order.getId());
		assertThat(saved.getAggregateType()).isEqualTo("ORDER");
		assertThat(saved.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(saved.getTopic()).isEqualTo("order-events");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(saved.getPublishedAt()).isNull();

		JsonNode envelope = objectMapper.readTree(saved.getPayload());
		assertThat(envelope.path("eventId").stringValue()).isEqualTo(saved.getId().toString());
		assertThat(envelope.path("eventType").stringValue()).isEqualTo("ORDER_PAID");
		assertThat(envelope.path("version").intValue()).isEqualTo(1);
		assertThat(LocalDateTime.parse(envelope.path("occurredAt").stringValue())).isEqualTo(APPROVED_AT);
		assertThat(envelope.path("aggregateId").stringValue()).isEqualTo(order.getId().toString());

		JsonNode payload = envelope.path("payload");
		assertThat(payload.path("orderId").stringValue()).isEqualTo(order.getId().toString());
		assertThat(payload.path("buyerId").stringValue()).isEqualTo(BUYER_ID.toString());
		assertThat(payload.path("totalOrderAmount").intValue()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.path("totalProductCount").intValue()).isEqualTo(TOTAL_ITEM_COUNT);
		assertThat(LocalDateTime.parse(payload.path("paidAt").stringValue())).isEqualTo(APPROVED_AT);
		assertThat(payload.path("products")).hasSize(order.getOrderProducts().size());

		JsonNode firstProduct = payload.path("products").get(0);
		assertThat(firstProduct.path("orderProductId").stringValue())
			.isEqualTo(order.getOrderProducts().get(0).getId().toString());
		assertThat(firstProduct.path("productId").stringValue()).isEqualTo(PRODUCT_ID_1.toString());
		assertThat(firstProduct.path("sellerId").stringValue()).isEqualTo(SELLER_ID_1.toString());
		assertThat(firstProduct.path("productTitle").stringValue()).isEqualTo(PRODUCT_TITLE_1);
		assertThat(firstProduct.path("productType").stringValue()).isEqualTo(PRODUCT_TYPE_PROMPT);
		assertThat(firstProduct.path("productAmount").intValue()).isEqualTo(PRODUCT_AMOUNT_1);
	}

	@Test
	@DisplayName("ORDER_REFUND payload를 JSON으로 직렬화해 OutboxEvent를 저장한다")
	void appendOrderRefund_savesSerializedOutboxEvent() throws Exception {
		Order order = createPaidOrderWithProducts();
		order.refund(REFUNDED_AT);
		PaymentRefundedEvent event = createPaymentRefundedEvent(order.getId());
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);

		appender.appendOrderRefund(order, event);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getAggregateId()).isEqualTo(order.getId());
		assertThat(saved.getAggregateType()).isEqualTo("ORDER");
		assertThat(saved.getEventType()).isEqualTo("ORDER_REFUND");
		assertThat(saved.getTopic()).isEqualTo("order-events");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(REFUNDED_AT);
		assertThat(saved.getPublishedAt()).isNull();

		JsonNode envelope = objectMapper.readTree(saved.getPayload());
		assertThat(envelope.path("eventId").stringValue()).isEqualTo(saved.getId().toString());
		assertThat(envelope.path("eventType").stringValue()).isEqualTo("ORDER_REFUND");
		assertThat(envelope.path("version").intValue()).isEqualTo(1);
		assertThat(LocalDateTime.parse(envelope.path("occurredAt").stringValue())).isEqualTo(REFUNDED_AT);
		assertThat(envelope.path("aggregateId").stringValue()).isEqualTo(order.getId().toString());

		JsonNode payload = envelope.path("payload");
		assertThat(payload.path("orderId").stringValue()).isEqualTo(order.getId().toString());
		assertThat(payload.path("paymentId").stringValue()).isEqualTo(PAYMENT_ID.toString());
		assertThat(payload.path("buyerId").stringValue()).isEqualTo(BUYER_ID.toString());
		assertThat(payload.path("totalRefundAmount").intValue()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.path("totalProductCount").intValue()).isEqualTo(TOTAL_ITEM_COUNT);
		assertThat(LocalDateTime.parse(payload.path("refundedAt").stringValue())).isEqualTo(REFUNDED_AT);
		assertThat(payload.path("products")).hasSize(order.getOrderProducts().size());

		JsonNode firstProduct = payload.path("products").get(0);
		assertThat(firstProduct.path("orderProductId").stringValue())
			.isEqualTo(order.getOrderProducts().get(0).getId().toString());
		assertThat(firstProduct.path("productId").stringValue()).isEqualTo(PRODUCT_ID_1.toString());
		assertThat(firstProduct.path("sellerId").stringValue()).isEqualTo(SELLER_ID_1.toString());
		assertThat(firstProduct.path("productTitle").stringValue()).isEqualTo(PRODUCT_TITLE_1);
		assertThat(firstProduct.path("productType").stringValue()).isEqualTo(PRODUCT_TYPE_PROMPT);
		assertThat(firstProduct.path("refundAmount").intValue()).isEqualTo(PRODUCT_AMOUNT_1);
	}
}
