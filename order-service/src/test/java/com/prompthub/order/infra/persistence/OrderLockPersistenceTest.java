package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.OrderAdapter;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({OrderAdapter.class, QuerydslConfig.class, TestJpaConfig.class})
class OrderLockPersistenceTest {

	private static final UUID BUYER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SELLER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID ORDER_A =
		UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID ORDER_B =
		UUID.fromString("00000000-0000-0000-0000-000000000502");
	private static final UUID ORDER_PRODUCT_A =
		UUID.fromString("00000000-0000-0000-0000-000000000601");
	private static final UUID ORDER_PRODUCT_B =
		UUID.fromString("00000000-0000-0000-0000-000000000602");

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderRepository orderRepository;

	@Test
	@DisplayName("주문과 주문상품을 잠그고 초기화된 Aggregate를 반환한다")
	void findAllByIdsWithOrderProductsForUpdate_returnsLockedAggregates() {
		entityManager.persist(order(ORDER_B, ORDER_PRODUCT_B, "ORD-B", 202));
		entityManager.persist(order(ORDER_A, ORDER_PRODUCT_A, "ORD-A", 201));
		entityManager.flush();
		entityManager.clear();

		List<Order> result = orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_B, ORDER_A));

		assertThat(result)
			.extracting(Order::getId)
			.containsExactly(ORDER_A, ORDER_B);
		assertThat(result)
			.allSatisfy(order -> assertThat(order.getOrderProducts()).hasSize(1));
		assertThat(result)
			.allSatisfy(order -> assertThat(entityManager.getEntityManager().getLockMode(order))
				.isEqualTo(LockModeType.PESSIMISTIC_WRITE));
		assertThat(result)
			.flatExtracting(Order::getOrderProducts)
			.allSatisfy(product -> assertThat(entityManager.getEntityManager().getLockMode(product))
				.isEqualTo(LockModeType.PESSIMISTIC_WRITE));
	}

	private Order order(UUID orderId, UUID orderProductId, String orderNumber, long productSuffix) {
		UUID productId = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(productSuffix));
		Order order = Order.create(BUYER_ID, orderNumber, 1_000);
		OrderProduct product = OrderProduct.create(productId, SELLER_ID, "상품", 1_000);
		ReflectionTestUtils.setField(order, "id", orderId);
		ReflectionTestUtils.setField(product, "id", orderProductId);
		order.addOrderProduct(product);
		return order;
	}
}
