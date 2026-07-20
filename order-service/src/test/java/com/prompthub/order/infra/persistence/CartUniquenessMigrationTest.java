package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartUniquenessMigrationTest extends PostgreSqlIntegrationTestSupport {

	private static final UUID BUYER_A = uuid(1);
	private static final UUID BUYER_B = uuid(2);
	private static final UUID CART_A = uuid(101);
	private static final UUID CART_B = uuid(102);
	private static final UUID PRODUCT_A = uuid(201);
	private static final UUID PRODUCT_B = uuid(202);

	private final JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@Autowired
	CartUniquenessMigrationTest(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("delete from cart_product");
		jdbcTemplate.update("delete from cart");
	}

	@Test
	void migrationRejectsTwoCartsForSameBuyer() {
		insertCart(CART_A, BUYER_A);

		assertThatThrownBy(() -> insertCart(CART_B, BUYER_A))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(exception -> assertThat(rootCause(exception).getMessage())
				.contains("uk_cart_buyer_id"));
	}

	@Test
	void migrationRejectsDuplicateProductInSameCart() {
		insertCart(CART_A, BUYER_A);
		insertCartProduct(uuid(301), CART_A, PRODUCT_A);

		assertThatThrownBy(() -> insertCartProduct(uuid(302), CART_A, PRODUCT_A))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(exception -> assertThat(rootCause(exception).getMessage())
				.contains("uk_cart_product_cart_product"));
	}

	@Test
	void migrationAllowsDifferentBuyersAndDistinctCartProducts() {
		insertCart(CART_A, BUYER_A);
		insertCart(CART_B, BUYER_B);
		insertCartProduct(uuid(301), CART_A, PRODUCT_A);
		insertCartProduct(uuid(302), CART_A, PRODUCT_B);
		insertCartProduct(uuid(303), CART_B, PRODUCT_A);

		assertThat(count("cart")).isEqualTo(2);
		assertThat(count("cart_product")).isEqualTo(3);
	}

	private void insertCart(UUID cartId, UUID buyerId) {
		jdbcTemplate.update(
			"insert into cart (id, buyer_id, total_amount, created_at, updated_at) values (?, ?, 0, current_timestamp, current_timestamp)",
			cartId,
			buyerId
		);
	}

	private void insertCartProduct(UUID cartProductId, UUID cartId, UUID productId) {
		jdbcTemplate.update(
			"insert into cart_product (id, cart_id, product_id, added_at, created_at, updated_at) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp)",
			cartProductId,
			cartId,
			productId
		);
	}

	private long count(String table) {
		Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
		return count == null ? 0 : count;
	}

	private Throwable rootCause(Throwable exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
