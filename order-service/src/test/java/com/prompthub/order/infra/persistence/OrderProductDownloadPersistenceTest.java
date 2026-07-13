package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.OrderProductPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderProductDownloadPersistenceTest {

	@Autowired private TestEntityManager entityManager;
	@Autowired private OrderProductPersistence persistence;

	@Test
	void tryMarkDownloaded_paidProduct_onlyFirstUpdateSucceeds() {
		Order order = createPendingOrderWithProducts();
		order.markPaid();
		OrderProduct product = order.getOrderProducts().getFirst();
		entityManager.persistAndFlush(order);

		assertThat(persistence.tryMarkDownloaded(product.getId())).isEqualTo(1);
		assertThat(persistence.tryMarkDownloaded(product.getId())).isZero();
	}
}
