package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.event.refund.OrderRefundRequestedPayload;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxEventAppenderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	@DisplayName("아웃박스 추가는 메시지만 받는다")
	void append_acceptsOnlyEventMessage() throws Exception {
		Method append = OutboxEventAppender.class.getMethod("append", EventMessage.class);

		assertThat(append.getParameterCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("EventMessage를 직렬화하여 OutboxEvent로 저장한다")
	void append_savesSerializedOutboxEvent() throws Exception {
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);
		
		UUID eventId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.now();
		
		EventMessage<DummyPayload> message = new EventMessage<>(
			eventId,
			"ORDER_PAID",
			occurredAt,
			"ORDER",
			aggregateId,
			new DummyPayload("test")
		);

		appender.append(message);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getOrderId()).isEqualTo(aggregateId);
		assertThat(saved.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(occurredAt);
		assertThat(saved.getPublishedAt()).isNull();

		// 검증 로직 추가 가능
		assertThat(saved.getPayload()).contains("test");
	}

	@Test
	@DisplayName("부분 환불 요청 Outbox는 payment-service 단건 payload와 orderId aggregate를 사용한다")
	void append_orderRefundRequested_savesSingleProductContract() {
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);
		OrderEventMessageFactory messageFactory = new OrderEventMessageFactory();
		UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000501");
		UUID orderProductId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		UUID buyerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 15, 12, 0);
		OrderRefundRequestedPayload payload = new OrderRefundRequestedPayload(
			orderId,
			orderProductId,
			buyerId,
			10_000,
			requestedAt
		);
		assertThat(OrderRefundRequestedPayload.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("orderId", "orderProductId", "buyerId", "refundAmount", "requestedAt");

		appender.append(messageFactory.createOrderRefundRequestedMessage(orderId, payload));

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());
		OutboxEvent saved = captor.getValue();

		assertThat(saved.getOrderId()).isEqualTo(orderId);
		assertThat(saved.getEventType()).isEqualTo(OrderEventType.ORDER_REFUND_REQUESTED.code());
		assertThat(saved.getPayload())
			.contains("\"eventType\":\"ORDER_REFUND_REQUESTED\"")
			.contains("\"aggregateId\":\"" + orderId + "\"")
			.contains("\"orderId\":\"" + orderId + "\"")
			.contains("\"orderProductId\":\"" + orderProductId + "\"")
			.contains("\"buyerId\":\"" + buyerId + "\"")
			.contains("\"refundAmount\":10000")
			.contains("\"requestedAt\":\"2026-07-15T12:00:00\"")
			.doesNotContain("\"paymentId\"")
			.doesNotContain("\"refundRequestId\"")
			.doesNotContain("\"products\"");
	}

	private record DummyPayload(String data) {}
}
