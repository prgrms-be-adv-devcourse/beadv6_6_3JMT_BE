package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.persistence.cart.CartAdapter;
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

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({OrderAdapter.class, CartAdapter.class, QuerydslConfig.class, TestJpaConfig.class})
class AggregateRootLockPersistenceTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private CartRepository cartRepository;

	@Test
	@DisplayName("Order와 Cart 루트를 순서대로 잠근 뒤 초기화된 aggregate를 반환한다")
	void locksOrderThenCartRootAndInitializesChildren() {
		Order order = createdOrder();
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A);
		cart.addProduct(PRODUCT_B);
		entityManager.persist(order);
		entityManager.persist(cart);
		entityManager.flush();
		entityManager.clear();

		Order lockedOrder = orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A).orElseThrow();
		assertThat(entityManager.getEntityManager().getLockMode(lockedOrder))
			.isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(lockedOrder.getOrderProducts()).hasSize(4);

		Cart lockedCart = cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID).orElseThrow();
		assertThat(entityManager.getEntityManager().getLockMode(lockedCart))
			.isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(lockedCart.getCartProducts()).hasSize(2);
	}
}
