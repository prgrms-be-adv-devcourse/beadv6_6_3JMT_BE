package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefund;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderRefundPersistenceTest {

	@Autowired private TestEntityManager entityManager;
	@Autowired private OrderRefundPersistence persistence;

	@Test
	@DisplayName("환불 요청과 여러 환불 상품을 함께 저장한다")
	void save_multiProducts_cascades() {
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 13, 12, 0);
		Order order = createPaidOrderWithProducts();
		entityManager.persistAndFlush(order);
		OrderRefund refund = createRequestedRefund(order, UUID.randomUUID(), requestedAt);
		order.getOrderProducts().forEach(refund::addProduct);

		OrderRefund saved = persistence.saveAndFlush(refund);
		entityManager.clear();

		OrderRefund found = persistence.findByIdWithProducts(saved.getId()).orElseThrow();
		assertThat(found.getRefundProducts()).hasSize(2);
		assertThat(found.getTotalRefundAmount()).isEqualTo(30_000);
	}

	@Test
	@DisplayName("65초가 지난 요청을 재조정 대상으로 조회한다")
	void findDueRefunds_requestedAndDue_returnsRefund() {
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 13, 12, 0);
		Order order = createPaidOrderWithProducts();
		entityManager.persistAndFlush(order);
		OrderRefund refund = createRequestedRefund(order, UUID.randomUUID(), requestedAt);
		refund.addProduct(order.getOrderProducts().getFirst());
		persistence.saveAndFlush(refund);

		List<OrderRefund> found = persistence.findDueRefunds(requestedAt.plusSeconds(66), 10);

		assertThat(found).extracting(OrderRefund::getId).containsExactly(refund.getId());
	}
}
