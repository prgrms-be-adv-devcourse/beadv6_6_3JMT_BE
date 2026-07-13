package com.prompthub.product.infra.messaging.consumer.order;

import com.prompthub.product.application.service.OrderEventHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID PRODUCT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private OrderEventHandler orderEventHandler;

	@Mock
	private Acknowledgment acknowledgment;

	private OrderEventConsumer orderEventConsumer;

	@BeforeEach
	void setUp() {
		orderEventConsumer = new OrderEventConsumer(new ObjectMapper(), orderEventHandler);
	}

	@Test
	@DisplayName("ORDER_PAID 이벤트를 수신하면 eventId와 productIds로 handlePaid를 호출한다")
	void consume_orderPaid_callsHandlePaid() {
		String message = eventMessage("ORDER_PAID", List.of(PRODUCT_ID_1, PRODUCT_ID_2));
		ArgumentCaptor<List<UUID>> captor = productIdCaptor();

		orderEventConsumer.consume(message, acknowledgment);

		then(orderEventHandler).should().handlePaid(eq(EVENT_ID), any(), captor.capture());
		assertThat(captor.getValue()).containsExactlyInAnyOrder(PRODUCT_ID_1, PRODUCT_ID_2);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("ORDER_REFUND 이벤트를 수신하면 handleRefund를 호출한다")
	void consume_orderRefund_callsHandleRefund() {
		String message = eventMessage("ORDER_REFUND", List.of(PRODUCT_ID_1));
		ArgumentCaptor<List<UUID>> captor = productIdCaptor();

		orderEventConsumer.consume(message, acknowledgment);

		then(orderEventHandler).should().handleRefund(eq(EVENT_ID), any(), captor.capture());
		assertThat(captor.getValue()).containsExactly(PRODUCT_ID_1);
		then(acknowledgment).should().acknowledge();
	}

	@Nested
	@DisplayName("예외/비지원 케이스")
	class EdgeCases {

		@Test
		@DisplayName("지원하지 않는 eventType은 핸들러를 호출하지 않고 acknowledge한다(DLT 아님)")
		void consume_unsupportedEventType_acknowledge() {
			String message = eventMessage("ORDER_SHIPPED", List.of(PRODUCT_ID_1));

			orderEventConsumer.consume(message, acknowledgment);

			then(orderEventHandler).shouldHaveNoInteractions();
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventId가 없으면 예외를 던져 DLT로 보낸다")
		void consume_missingEventId_throws() {
			String message = """
				{"eventType":"ORDER_PAID","payload":{"products":[]}}
				""";

			assertThatThrownBy(() -> orderEventConsumer.consume(message, acknowledgment))
				.isInstanceOf(IllegalArgumentException.class);
			then(orderEventHandler).should(never()).handlePaid(any(), any(), any());
			then(acknowledgment).should(never()).acknowledge();
		}
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<UUID>> productIdCaptor() {
		return ArgumentCaptor.forClass((Class<List<UUID>>) (Class<?>) List.class);
	}

	private String eventMessage(String eventType, List<UUID> productIds) {
		String products = productIds.stream()
			.map(id -> "{\"productId\":\"" + id + "\"}")
			.reduce((a, b) -> a + "," + b)
			.orElse("");
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + eventType
			+ "\",\"aggregateType\":\"ORDER\",\"payload\":{\"products\":[" + products + "]}}";
	}
}
