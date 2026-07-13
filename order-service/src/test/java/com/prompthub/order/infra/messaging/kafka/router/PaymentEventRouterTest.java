package com.prompthub.order.infra.messaging.kafka.router;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.payment.PaymentApprovedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentCanceledProcessor;
import com.prompthub.order.application.service.event.payment.PaymentFailedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundCompletedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundFailedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundedProcessor;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventRouterTest {

	@Mock private PaymentApprovedProcessor approvedProcessor;
	@Mock private PaymentRefundedProcessor refundedProcessor;
	@Mock private PaymentFailedProcessor failedProcessor;
	@Mock private PaymentCanceledProcessor canceledProcessor;
	@Mock private PaymentRefundCompletedProcessor refundCompletedProcessor;
	@Mock private PaymentRefundFailedProcessor refundFailedProcessor;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private PaymentEventRouter paymentEventRouter;

	@BeforeEach
	void setUp() {
		paymentEventRouter = new PaymentEventRouter(
			new EventPayloadMapper(objectMapper),
			approvedProcessor,
			refundedProcessor,
			failedProcessor,
			canceledProcessor,
			refundCompletedProcessor,
			refundFailedProcessor
		);
	}

	@Test
	@DisplayName("payment.failed 이벤트를 변환하여 실패 Processor로 위임한다")
	void route_paymentFailed_delegatesToProcessor() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 13, 12, 0);
		JsonNode payload = objectMapper.readTree("""
			{
			  "orderId": "%s",
			  "paymentId": "%s",
			  "buyerId": "%s",
			  "failureReason": "승인 거절",
			  "failedAt": "2026-07-13T12:00:00"
			}
			""".formatted(orderId, paymentId, buyerId));
		EventMessage<JsonNode> message = new EventMessage<>(
			eventId, "payment.failed", occurredAt, "PAYMENT", paymentId, payload
		);

		paymentEventRouter.route(message);

		ArgumentCaptor<ConsumedEventContext> contextCaptor = ArgumentCaptor.forClass(ConsumedEventContext.class);
		ArgumentCaptor<PaymentFailedPayload> payloadCaptor = ArgumentCaptor.forClass(PaymentFailedPayload.class);
		verify(failedProcessor).process(contextCaptor.capture(), payloadCaptor.capture());
		assertThat(contextCaptor.getValue().eventId()).isEqualTo(eventId);
		assertThat(contextCaptor.getValue().eventType()).isEqualTo("payment.failed");
		assertThat(payloadCaptor.getValue().orderId()).isEqualTo(orderId);
		assertThat(payloadCaptor.getValue().failureReason()).isEqualTo("승인 거절");
	}

	@Test
	@DisplayName("알 수 없는 이벤트 타입은 Processor에 위임하지 않는다")
	void route_unknownEvent_returnsGracefully() throws Exception {
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(), "UNKNOWN_EVENT_TYPE", LocalDateTime.now(), "PAYMENT", UUID.randomUUID(),
			objectMapper.readTree("{}")
		);

		paymentEventRouter.route(message);

		verify(approvedProcessor, never()).process(any(), any());
		verify(refundedProcessor, never()).process(any(), any());
		verify(failedProcessor, never()).process(any(), any());
		verify(canceledProcessor, never()).process(any(), any());
		verify(refundCompletedProcessor, never()).process(any(), any());
		verify(refundFailedProcessor, never()).process(any(), any());
	}
}
