package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventConsumer;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventHandler;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class PaymentEventConsumerTest {

	private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private PaymentEventHandler paymentEventHandler;
	private Acknowledgment acknowledgment;
	private PaymentEventConsumer consumer;

	@BeforeEach
	void setUp() {
		paymentEventHandler = mock(PaymentEventHandler.class);
		acknowledgment = mock(Acknowledgment.class);
		consumer = new PaymentEventConsumer(new ObjectMapper(), paymentEventHandler);
	}

	@Test
	@DisplayName("PAYMENT_APPROVED 수신 시 payload를 PaymentEventHandler로 위임하고 ack 한다")
	void consume_paymentApprovedEnvelope_delegatesPayloadAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_APPROVED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {
			    "paymentId": "%s",
			    "orderId": "%s",
			    "userId": "%s",
			    "amount": 30000,
			    "approvedAt": "2026-06-19T12:00:00+09:00"
			  }
			}
			""".formatted(UUID.randomUUID(), PAYMENT_ID, ORDER_ID, BUYER_ID);

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should().handle(eq(PaymentEventType.PAYMENT_APPROVED), eq("PAYMENT_APPROVED"), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_REFUNDED 수신 시 payload를 PaymentEventHandler로 위임하고 ack 한다")
	void consume_paymentRefundedEnvelope_delegatesPayloadAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_REFUNDED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {
			    "paymentId": "%s",
			    "orderId": "%s",
			    "userId": "%s",
			    "amount": 30000,
			    "refundedAt": "2026-06-19T12:00:00+09:00"
			  }
			}
			""".formatted(UUID.randomUUID(), PAYMENT_ID, ORDER_ID, BUYER_ID);

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should().handle(eq(PaymentEventType.PAYMENT_REFUNDED), eq("PAYMENT_REFUNDED"), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("필수 필드 eventType 누락 시 무시하고 ack 한다")
	void consume_missingFields_ignoresAndAcknowledges() {
		String message = """
			{
			  "paymentId": "%s",
			  "orderId": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID);

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should(never()).handle(any(), any(), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("알 수 없는 eventType은 handler 호출 없이 ack 한다")
	void consume_unknownEventType_acknowledgesWithoutDelegating() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_EXPIRED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {}
			}
			""".formatted(UUID.randomUUID());

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should(never()).handle(any(), any(), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_FAILED는 handler 호출 없이 ack 한다")
	void consume_paymentFailed_acknowledgesWithoutDelegating() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_FAILED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {}
			}
			""".formatted(UUID.randomUUID());

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should(never()).handle(any(), any(), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_CANCELED는 handler 호출 없이 ack 한다")
	void consume_paymentCanceled_acknowledgesWithoutDelegating() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_CANCELED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {}
			}
			""".formatted(UUID.randomUUID());

		consumer.consume(message, acknowledgment);

		then(paymentEventHandler).should(never()).handle(any(), any(), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("기타 예외 발생 시에는 ack 하지 않고 예외를 던진다")
	void consume_otherException_throwsWithoutAcknowledging() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_APPROVED",
			  "occurredAt": "2026-06-19T12:00:00+09:00",
			  "payload": {
			    "paymentId": "%s",
			    "orderId": "%s",
			    "userId": "%s",
			    "amount": 30000,
			    "approvedAt": "2026-06-19T12:00:00+09:00"
			  }
			}
			""".formatted(UUID.randomUUID(), PAYMENT_ID, ORDER_ID, BUYER_ID);

		willThrow(new RuntimeException("DB Connection Error"))
			.given(paymentEventHandler).handle(any(), any(), any());

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(RuntimeException.class);

		then(acknowledgment).should(never()).acknowledge();
	}

	@Test
	@DisplayName("처리 대상 이벤트의 payload 누락은 ack 하지 않고 예외를 전파한다")
	void consume_missingPayload_throwsWithoutAcknowledging() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_APPROVED",
			  "occurredAt": "2026-06-19T12:00:00+09:00"
			}
			""".formatted(UUID.randomUUID());

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(OrderException.class);

		then(paymentEventHandler).should(never()).handle(any(), any(), any());
		then(acknowledgment).should(never()).acknowledge();
	}

	@Test
	@DisplayName("JSON 파싱 실패는 ack 하지 않고 예외를 전파한다")
	void consume_invalidJson_throwsWithoutAcknowledging() {
		String message = "{";

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(OrderException.class);

		then(acknowledgment).should(never()).acknowledge();
	}
}
