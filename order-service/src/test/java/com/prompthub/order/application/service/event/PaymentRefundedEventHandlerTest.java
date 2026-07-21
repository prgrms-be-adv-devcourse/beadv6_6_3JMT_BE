package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class PaymentRefundedEventHandlerTest {

	@Mock
	private PaymentRefundedProcessor processor;

	private ObjectMapper objectMapper;
	private PaymentRefundedEventHandler handler;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		handler = new PaymentRefundedEventHandler(new EventPayloadMapper(objectMapper), processor);
	}

	@Test
	void handle_mapsCurrentPaymentPayload() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.now();
		JsonNode payload = objectMapper.readTree("""
			{"orderId":"%s","refundAmount":30000,"refundedAt":"2026-07-17T11:00:00+09:00"}
			""".formatted(orderId));
		EventMessage<JsonNode> message = new EventMessage<>(
			eventId, "PAYMENT_REFUNDED", occurredAt, "ORDER", orderId, payload
		);

		handler.handle(message);

		ArgumentCaptor<PaymentRefundedPayload> captor = ArgumentCaptor.forClass(PaymentRefundedPayload.class);
		then(processor).should().process(eq(eventId), eq("PAYMENT_REFUNDED"), eq(occurredAt), captor.capture());
		assertThat(captor.getValue().orderId()).isEqualTo(orderId);
		assertThat(captor.getValue().refundAmount()).isEqualTo(30_000);
	}

	@Test
	void handleFailed_mapsCurrentPaymentPayload() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.now();
		JsonNode payload = objectMapper.readTree("""
			{"orderId":"%s","refundAmount":30000,"failedAt":"2026-07-17T11:00:00+09:00"}
			""".formatted(orderId));
		EventMessage<JsonNode> message = new EventMessage<>(
			eventId, "PAYMENT_REFUND_FAILED", occurredAt, "ORDER", orderId, payload
		);

		handler.handleFailed(message);

		ArgumentCaptor<PaymentRefundedEventHandler.RefundFailedPayload> captor =
			ArgumentCaptor.forClass(PaymentRefundedEventHandler.RefundFailedPayload.class);
		then(processor).should().processFailed(
			eq(eventId),
			eq("PAYMENT_REFUND_FAILED"),
			eq(occurredAt),
			captor.capture()
		);
		assertThat(captor.getValue().orderId()).isEqualTo(orderId);
		assertThat(captor.getValue().refundAmount()).isEqualTo(30_000);
	}
}
