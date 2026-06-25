package com.prompthub.settlement.infrastructure.messaging.kafka.consumer.order;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.event.OrderPaidProduct;
import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.global.exception.SettlementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class OrderEventConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID BUYER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID ORDER_PRODUCT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID SELLER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
	private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 15, 10, 0);

	private SettlementSourceUseCase settlementSourceUseCase;
	private Acknowledgment acknowledgment;
	private OrderEventConsumer consumer;

	@BeforeEach
	void setUp() {
		settlementSourceUseCase = mock(SettlementSourceUseCase.class);
		acknowledgment = mock(Acknowledgment.class);
		consumer = new OrderEventConsumer(new ObjectMapper(), settlementSourceUseCase);
	}

	@Test
	@DisplayName("ORDER_PAID 메시지를 envelope로 역직렬화해 위임하고 ack 한다")
	void consume_orderPaid_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "ORDER_PAID",
			  "version": 1,
			  "occurredAt": "%s",
			  "aggregateId": "%s",
			  "payload": {
			    "orderId": "%s",
			    "buyerId": "%s",
			    "totalOrderAmount": 9900,
			    "totalProductCount": 1,
			    "paidAt": "%s",
			    "products": [
			      {
			        "orderProductId": "%s",
			        "productId": "%s",
			        "sellerId": "%s",
			        "productTitle": "프롬프트",
			        "productType": "PROMPT",
			        "productAmount": 9900
			      }
			    ]
			  }
			}
			""".formatted(EVENT_ID, EVENT_TIME, ORDER_ID, ORDER_ID, BUYER_ID, EVENT_TIME,
				ORDER_PRODUCT_ID, PRODUCT_ID, SELLER_ID);

		consumer.consume(message, acknowledgment);

		OrderEventEnvelope<OrderPaidEvent> expected = new OrderEventEnvelope<>(
			EVENT_ID, "ORDER_PAID", 1, EVENT_TIME, ORDER_ID,
			new OrderPaidEvent(ORDER_ID, BUYER_ID, 9900, 1, EVENT_TIME, List.of(
				new OrderPaidProduct(ORDER_PRODUCT_ID, PRODUCT_ID, SELLER_ID, "프롬프트", "PROMPT", 9900))));
		then(settlementSourceUseCase).should().recordOrderPaid(expected);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("지원하지 않는 eventType은 위임 없이 ack 한다")
	void consume_unknownEventType_ignoresAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "ORDER_SHIPPED",
			  "version": 1,
			  "occurredAt": "%s",
			  "aggregateId": "%s",
			  "payload": {}
			}
			""".formatted(EVENT_ID, EVENT_TIME, ORDER_ID);

		consumer.consume(message, acknowledgment);

		then(settlementSourceUseCase).should(never()).recordOrderPaid(any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("JSON 파싱 실패는 ack 하지 않고 예외를 전파한다")
	void consume_invalidJson_throwsWithoutAcknowledging() {
		assertThatThrownBy(() -> consumer.consume("{", acknowledgment))
			.isInstanceOf(SettlementException.class);

		then(acknowledgment).should(never()).acknowledge();
	}
}
