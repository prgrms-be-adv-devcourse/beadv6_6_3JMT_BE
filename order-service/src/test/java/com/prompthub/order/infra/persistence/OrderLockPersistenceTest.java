package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
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

import java.util.Optional;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({OrderAdapter.class, QuerydslConfig.class, TestJpaConfig.class})
class OrderLockPersistenceTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderRepository orderRepository;

	@Test
	@DisplayName("단건 주문 Root와 UUID 순 주문상품을 잠그고 초기화된 Aggregate를 반환한다")
	void findByIdWithOrderProductsForUpdate_returnsLockedAggregate() {
		entityManager.persist(createdOrder());
		entityManager.flush();
		entityManager.clear();

		Optional<Order> result = orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A);

		assertThat(result).isPresent();
		Order order = result.orElseThrow();
		assertThat(order.getOrderProducts()).hasSize(4);
		assertThat(entityManager.getEntityManager().getLockMode(order))
			.isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(order.getOrderProducts())
			.allSatisfy(product -> assertThat(entityManager.getEntityManager().getLockMode(product))
				.isEqualTo(LockModeType.PESSIMISTIC_WRITE));
	}
}
