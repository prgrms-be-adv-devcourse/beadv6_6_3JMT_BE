package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentCanceledEvent;
import com.prompthub.order.application.event.PaymentFailedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.application.service.OrderPaymentEventService;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class PaymentEventConsumerTest {

	private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 23, 10, 30);

	private OrderPaymentEventService orderPaymentEventService;
	private Acknowledgment acknowledgment;
	private PaymentEventConsumer consumer;

	@BeforeEach
	void setUp() {
		orderPaymentEventService = mock(OrderPaymentEventService.class);
		acknowledgment = mock(Acknowledgment.class);
		consumer = new PaymentEventConsumer(new ObjectMapper(), orderPaymentEventService);
	}

	@Test
	@DisplayName("PAYMENT_APPROVED 이벤트는 승인 처리로 위임하고 ack 한다")
	void consume_paymentApproved_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventType": "PAYMENT_APPROVED",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "buyerId": "%s",
			  "approvedAmount": 30000,
			  "approvedAt": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID, EVENT_TIME);

		consumer.consume(message, acknowledgment);

		then(orderPaymentEventService).should().handlePaymentApproved(new PaymentApprovedEvent(
			PAYMENT_ID,
			ORDER_ID,
			BUYER_ID,
			30000,
			EVENT_TIME
		));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_FAILED 이벤트는 실패 처리로 위임하고 ack 한다")
	void consume_paymentFailed_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventType": "PAYMENT_FAILED",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "buyerId": "%s",
			  "reason": "PG 승인 실패",
			  "failedAt": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID, EVENT_TIME);

		consumer.consume(message, acknowledgment);

		then(orderPaymentEventService).should().handlePaymentFailed(new PaymentFailedEvent(
			PAYMENT_ID,
			ORDER_ID,
			BUYER_ID,
			"PG 승인 실패",
			EVENT_TIME
		));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_CANCELED 이벤트는 취소 처리로 위임하고 ack 한다")
	void consume_paymentCanceled_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventType": "PAYMENT_CANCELED",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "buyerId": "%s",
			  "canceledAt": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID, EVENT_TIME);

		consumer.consume(message, acknowledgment);

		then(orderPaymentEventService).should().handlePaymentCanceled(new PaymentCanceledEvent(
			PAYMENT_ID,
			ORDER_ID,
			BUYER_ID,
			EVENT_TIME
		));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PAYMENT_REFUNDED 이벤트는 환불 처리로 위임하고 ack 한다")
	void consume_paymentRefunded_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventType": "PAYMENT_REFUNDED",
			  "paymentId": "%s",
			  "orderId": "%s",
			  "buyerId": "%s",
			  "refundedAmount": 30000,
			  "refundedAt": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID, BUYER_ID, EVENT_TIME);

		consumer.consume(message, acknowledgment);

		then(orderPaymentEventService).should().handlePaymentRefunded(new PaymentRefundedEvent(
			PAYMENT_ID,
			ORDER_ID,
			BUYER_ID,
			30000,
			EVENT_TIME
		));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("지원하지 않는 eventType은 서비스 호출 없이 ack 한다")
	void consume_unknownEventType_ignoresAndAcknowledges() {
		String message = """
			{
			  "eventType": "PAYMENT_UNKNOWN",
			  "paymentId": "%s",
			  "orderId": "%s"
			}
			""".formatted(PAYMENT_ID, ORDER_ID);

		consumer.consume(message, acknowledgment);

		then(orderPaymentEventService).should(never()).handlePaymentApproved(any(PaymentApprovedEvent.class));
		then(orderPaymentEventService).should(never()).handlePaymentFailed(any(PaymentFailedEvent.class));
		then(orderPaymentEventService).should(never()).handlePaymentCanceled(any(PaymentCanceledEvent.class));
		then(orderPaymentEventService).should(never()).handlePaymentRefunded(any(PaymentRefundedEvent.class));
		then(acknowledgment).should().acknowledge();
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
