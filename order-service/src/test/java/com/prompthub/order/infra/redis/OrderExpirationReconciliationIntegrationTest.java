package com.prompthub.order.infra.redis;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationMetrics;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

class OrderExpirationReconciliationIntegrationTest extends PostgreSqlIntegrationTestSupport {

	@Autowired
	private OrderFailureCompensationService compensationService;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private CartRepository cartRepository;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OrderProductIdempotencyStore orderProductIdempotencyStore;

	@MockitoBean
	private OrderExpirationMetrics expirationMetrics;

	@Test
	void databaseReconciliationFailsExpiredOrderWithoutPaymentEvent() {
		Order order = orderRepository.saveAndFlush(createdOrder());
		LocalDateTime timedOutAt = order.getCreatedAt().plusMinutes(20).plusSeconds(1);
		Clock clock = Clock.fixed(
			timedOutAt.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
			ZoneId.of("Asia/Seoul")
		);
		OrderExpirationWorker worker = new OrderExpirationWorker(
			orderExpirationStore,
			compensationService,
			orderRepository,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30),
			clock,
			expirationMetrics
		);
		willThrow(new IllegalStateException("redis down"))
			.given(orderExpirationStore)
			.findExpiredOrderIds(any(Instant.class), eq(100));

		worker.processExpiredOrders();

		Order restored = orderRepository.findByIdWithOrderProducts(order.getId())
			.orElseThrow();
		assertThat(restored.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(restored.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
			.hasValueSatisfying(cart -> assertThat(cart.getCartProducts())
				.extracting(CartProduct::getProductId)
				.containsExactlyInAnyOrderElementsOf(productIds()));
		then(orderExpirationStore).should()
			.findExpiredOrderIds(any(Instant.class), eq(100));
		then(orderProductIdempotencyStore).should().release(
			eq(BUYER_ID),
			eq(productIds().stream().sorted().toList()),
			eq(order.getId())
		);
	}

	private Order createdOrder() {
		Order order = Order.create(BUYER_ID, "ORD-RECONCILE", AMOUNT_A1 + AMOUNT_A2);
		order.addOrderProduct(
			OrderProduct.create(PRODUCT_A1, SELLER_A, "상품 A1", AMOUNT_A1)
		);
		order.addOrderProduct(
			OrderProduct.create(PRODUCT_A2, SELLER_A, "상품 A2", AMOUNT_A2)
		);
		return order;
	}

	private List<UUID> productIds() {
		return List.of(PRODUCT_A1, PRODUCT_A2);
	}
}
