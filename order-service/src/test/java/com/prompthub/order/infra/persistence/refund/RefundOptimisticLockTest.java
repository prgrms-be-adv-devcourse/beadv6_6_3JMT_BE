package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundOptimisticLockTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 12, 0);

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	@DisplayName("주문 동시 수정은 오래된 버전의 갱신을 거부한다")
	void order_concurrentUpdate_throwsOptimisticLockException() {
		Order order = Order.create(UUID.randomUUID(), uniqueOrderNumber(), 10_000, 0);
		persist(order);

		assertStaleWriteFails(Order.class, order.getId(), Order::markFailed);
	}

	@Test
	@DisplayName("주문상품 동시 수정은 오래된 버전의 갱신을 거부한다")
	void orderProduct_concurrentUpdate_throwsOptimisticLockException() {
		Order order = Order.create(UUID.randomUUID(), uniqueOrderNumber(), 10_000, 1);
		OrderProduct product = OrderProduct.create(
			UUID.randomUUID(), UUID.randomUUID(), "상품", "PROMPT", "GPT-4.1", 10_000
		);
		order.addOrderProduct(product);
		persist(order);

		assertStaleWriteFails(OrderProduct.class, product.getId(), OrderProduct::markFailed);
	}

	@Test
	@DisplayName("환불 요청 동시 수정은 오래된 버전의 갱신을 거부한다")
	void refund_concurrentUpdate_throwsOptimisticLockException() {
		OrderRefund refund = OrderRefund.request(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			10_000, NOW, NOW.plusMinutes(10)
		);
		persist(refund);

		assertStaleWriteFails(
			OrderRefund.class,
			refund.getId(),
			loaded -> loaded.scheduleNextCheck(NOW.plusMinutes(20))
		);
	}

	private <T> void assertStaleWriteFails(Class<T> entityType, UUID id, Consumer<T> update) {
		EntityManager first = entityManagerFactory.createEntityManager();
		EntityManager second = entityManagerFactory.createEntityManager();
		try {
			first.getTransaction().begin();
			second.getTransaction().begin();
			update.accept(first.find(entityType, id));
			update.accept(second.find(entityType, id));
			first.getTransaction().commit();

			assertThatThrownBy(second.getTransaction()::commit)
				.isInstanceOf(RollbackException.class)
				.hasCauseInstanceOf(OptimisticLockException.class);
		} finally {
			close(first);
			close(second);
		}
	}

	private void persist(Object entity) {
		EntityManager entityManager = entityManagerFactory.createEntityManager();
		try {
			entityManager.getTransaction().begin();
			entityManager.persist(entity);
			entityManager.getTransaction().commit();
		} finally {
			close(entityManager);
		}
	}

	private String uniqueOrderNumber() {
		return "ORD-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private void close(EntityManager entityManager) {
		EntityTransaction transaction = entityManager.getTransaction();
		if (transaction.isActive()) {
			transaction.rollback();
		}
		entityManager.close();
	}
}
