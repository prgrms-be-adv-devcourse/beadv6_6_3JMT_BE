package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.support.ConcurrentScenarioRunner;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static org.assertj.core.api.Assertions.assertThat;

class OrderProductPendingUniquenessConcurrencyTest extends PostgreSqlIntegrationTestSupport {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@RepeatedTest(5)
	void concurrentPendingOrdersPersistOnlyOneProduct() {
		Order first = order("ORD-CONCURRENT-A");
		Order second = order("ORD-CONCURRENT-B");

		try (ConcurrentScenarioRunner runner = new ConcurrentScenarioRunner(transactionManager, 10)) {
			ConcurrentScenarioRunner.Results results = runner.run(
				() -> orderRepository.saveAndFlush(first),
				() -> orderRepository.saveAndFlush(second)
			);

			List<Throwable> failures = Stream.of(
					results.firstFailure(),
					results.secondFailure()
				)
				.filter(Objects::nonNull)
				.toList();

			assertThat(failures)
				.singleElement()
				.isInstanceOf(OrderException.class)
				.hasFieldOrPropertyWithValue(
					"errorCode",
					ErrorCode.ORDER_PRODUCT_ALREADY_OWNED
				);
			assertThat(jdbcTemplate.queryForObject(
				"select count(*) from order_product",
				Long.class
			)).isEqualTo(1L);
		}
	}

	private Order order(String orderNumber) {
		Order order = Order.create(BUYER_ID, orderNumber, 10_000);
		order.addOrderProduct(
			OrderProduct.create(PRODUCT_A1, SELLER_A, "상품", 10_000)
		);
		return order;
	}
}
