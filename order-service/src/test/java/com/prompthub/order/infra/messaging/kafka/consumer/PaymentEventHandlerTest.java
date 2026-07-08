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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

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
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "approvedAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID));

		handler.handle(PaymentEventType.PAYMENT_APPROVED, "PAYMENT_APPROVED", payload);

		ArgumentCaptor<PaymentApprovedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentApprovedEvent.class);
		then(orderPaymentEventService).should().handlePaymentApproved(eventCaptor.capture());
		PaymentApprovedEvent event = eventCaptor.getValue();
		assertThat(event.eventType()).isEqualTo("PAYMENT_APPROVED");
		assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.userId()).isEqualTo(BUYER_ID);
		assertThat(event.amount()).isEqualTo(30000);
		assertThat(event.approvedAt().toString()).isEqualTo("2026-06-19T12:00+09:00");
	}

	@Test
	@DisplayName("payment-service 환불 메시지 스키마를 PaymentRefundedEvent로 변환해 처리한다")
	void handle_paymentRefundedMessageSchema_delegatesRefundEvent() {
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "refundedAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID));

		handler.handle(PaymentEventType.PAYMENT_REFUNDED, "PAYMENT_REFUNDED", payload);

		ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
		then(orderPaymentEventService).should().handlePaymentRefunded(eventCaptor.capture());
		PaymentRefundedEvent event = eventCaptor.getValue();
		assertThat(event.eventType()).isEqualTo("PAYMENT_REFUNDED");
		assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.userId()).isEqualTo(BUYER_ID);
		assertThat(event.amount()).isEqualTo(30000);
		assertThat(event.refundedAt().toString()).isEqualTo("2026-06-19T12:00+09:00");
	}

	@Test
	@DisplayName("승인 payload의 필수 UUID 필드가 누락되면 예외가 발생한다")
	void handle_paymentApprovedPayloadMissingRequiredUuid_throwsException() {
		JsonNode payload = objectMapper.readTree("""
			{
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "approvedAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(ORDER_ID, BUYER_ID));

		assertThatThrownBy(() -> handler.handle(PaymentEventType.PAYMENT_APPROVED, "PAYMENT_APPROVED", payload))
			.isInstanceOf(RuntimeException.class);

		then(orderPaymentEventService).should(never()).handlePaymentApproved(any());
	}

	@Test
	@DisplayName("환불 payload의 일시 형식이 잘못되면 예외가 발생한다")
	void handle_paymentRefundedPayloadInvalidDateTime_throwsException() {
		JsonNode payload = objectMapper.readTree("""
			{
			  "paymentId": "%s",
			  "orderId": "%s",
			  "userId": "%s",
			  "amount": 30000,
			  "refundedAt": "not-date-time"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID));

		assertThatThrownBy(() -> handler.handle(PaymentEventType.PAYMENT_REFUNDED, "PAYMENT_REFUNDED", payload))
			.isInstanceOf(RuntimeException.class);

		then(orderPaymentEventService).should(never()).handlePaymentRefunded(any());
	}
}
