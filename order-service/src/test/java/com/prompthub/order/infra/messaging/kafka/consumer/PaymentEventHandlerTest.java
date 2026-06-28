package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.OrderPaymentEventService;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventHandler;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class PaymentEventHandlerTest {

	private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private ObjectMapper objectMapper;
	private OrderPaymentEventService orderPaymentEventService;
	private PaymentEventHandler handler;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		orderPaymentEventService = mock(OrderPaymentEventService.class);
		handler = new PaymentEventHandler(orderPaymentEventService);
	}

	@Test
	@DisplayName("payment-service 승인 메시지 스키마를 PaymentApprovedEvent로 변환해 처리한다")
	void handle_paymentApprovedMessageSchema_delegatesApprovedEvent() {
		JsonNode root = objectMapper.readTree("""
			{
			  "eventType": "payment.approved",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "approvedAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID));

		handler.handle(PaymentEventType.PAYMENT_APPROVED, "payment.approved", "order-service", root);

		ArgumentCaptor<PaymentApprovedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentApprovedEvent.class);
		then(orderPaymentEventService).should().handlePaymentApproved(eventCaptor.capture());
		PaymentApprovedEvent event = eventCaptor.getValue();
		assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.userId()).isEqualTo(BUYER_ID);
		assertThat(event.amount()).isEqualTo(30000);
		assertThat(event.approvedAt().toString()).isEqualTo("2026-06-19T12:00+09:00");
	}

	@Test
	@DisplayName("payment-service 환불 메시지 스키마를 PaymentRefundedEvent로 변환해 처리한다")
	void handle_paymentRefundedMessageSchema_delegatesRefundEvent() {
		JsonNode root = objectMapper.readTree("""
			{
			  "eventType": "payment.refunded",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "refundedAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID));

		handler.handle(PaymentEventType.PAYMENT_REFUNDED, "payment.refunded", "order-service", root);

		ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
		then(orderPaymentEventService).should().handlePaymentRefunded(eventCaptor.capture());
		PaymentRefundedEvent event = eventCaptor.getValue();
		assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.userId()).isEqualTo(BUYER_ID);
		assertThat(event.amount()).isEqualTo(30000);
		assertThat(event.refundedAt().toString()).isEqualTo("2026-06-19T12:00+09:00");
	}
}
