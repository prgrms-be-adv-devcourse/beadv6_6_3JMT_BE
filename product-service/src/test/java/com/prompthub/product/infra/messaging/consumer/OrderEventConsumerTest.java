package com.prompthub.product.infra.messaging.consumer;

import com.prompthub.product.application.service.ProductSalesCountService;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

	private static final UUID PRODUCT_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID PRODUCT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private ProductSalesCountService productSalesCountService;

	@Mock
	private Acknowledgment acknowledgment;

	private OrderEventConsumer orderEventConsumer;

	@BeforeEach
	void setUp() {
		orderEventConsumer = new OrderEventConsumer(new ObjectMapper(), productSalesCountService);
	}

	@Nested
	@DisplayName("ORDER_PAID 이벤트")
	class OrderPaid {

		@Test
		@DisplayName("ORDER_PAID 이벤트를 수신하면 상품 salesCount를 증가시킨다")
		void consume_orderPaid_incrementsSalesCount() {
			String message = orderPaidMessage(List.of(PRODUCT_ID_1, PRODUCT_ID_2));
			ArgumentCaptor<List<UUID>> captor = productIdCaptor();

			orderEventConsumer.consume(message, acknowledgment);

			then(productSalesCountService).should().incrementSalesCount(captor.capture());
			assertThat(captor.getValue()).containsExactlyInAnyOrder(PRODUCT_ID_1, PRODUCT_ID_2);
			then(acknowledgment).should().acknowledge();
		}
	}

	@Nested
	@DisplayName("ORDER_REFUND 이벤트")
	class OrderRefund {

		@Test
		@DisplayName("ORDER_REFUND 이벤트를 수신하면 상품 salesCount를 감소시킨다")
		void consume_orderRefund_decrementsSalesCount() {
			String message = orderRefundMessage(List.of(PRODUCT_ID_1));
			ArgumentCaptor<List<UUID>> captor = productIdCaptor();

			orderEventConsumer.consume(message, acknowledgment);

			then(productSalesCountService).should().decrementSalesCount(captor.capture());
			assertThat(captor.getValue()).containsExactly(PRODUCT_ID_1);
			then(acknowledgment).should().acknowledge();
		}
	}

	@Nested
	@DisplayName("예외 케이스")
	class EdgeCases {

		@Test
		@DisplayName("알 수 없는 eventType은 서비스를 호출하지 않고 acknowledge한다")
		void consume_unknownEventType_noServiceCall() {
			String message = """
				{"eventType":"UNKNOWN_EVENT","payload":{"products":[]}}
				""";

			orderEventConsumer.consume(message, acknowledgment);

			then(productSalesCountService).should(never()).incrementSalesCount(org.mockito.ArgumentMatchers.any());
			then(productSalesCountService).should(never()).decrementSalesCount(org.mockito.ArgumentMatchers.any());
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventType이 없으면 서비스를 호출하지 않고 acknowledge한다")
		void consume_missingEventType_acknowledge() {
			String message = """
				{"payload":{"products":[]}}
				""";

			orderEventConsumer.consume(message, acknowledgment);

			then(productSalesCountService).should(never()).incrementSalesCount(org.mockito.ArgumentMatchers.any());
			then(acknowledgment).should().acknowledge();
		}
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<UUID>> productIdCaptor() {
		return ArgumentCaptor.forClass((Class<List<UUID>>) (Class<?>) List.class);
	}

	private String orderPaidMessage(List<UUID> productIds) {
		String products = productIds.stream()
			.map(id -> """
				{"productId":"%s","sellerId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","productTitle":"test","productType":"PROMPT","productAmount":9900}
				""".formatted(id))
			.reduce((a, b) -> a + "," + b)
			.orElse("");
		return """
			{"eventType":"ORDER_PAID","payload":{"orderId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","products":[%s]}}
			""".formatted(products);
	}

	private String orderRefundMessage(List<UUID> productIds) {
		String products = productIds.stream()
			.map(id -> """
				{"productId":"%s","sellerId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","productTitle":"test","productType":"PROMPT","refundAmount":9900}
				""".formatted(id))
			.reduce((a, b) -> a + "," + b)
			.orElse("");
		return """
			{"eventType":"ORDER_REFUND","payload":{"orderId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","products":[%s]}}
			""".formatted(products);
	}
}
