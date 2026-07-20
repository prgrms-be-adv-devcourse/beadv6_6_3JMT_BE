package com.prompthub.order.support;

import java.util.UUID;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgreSqlIntegrationTestSupportSmokeTest extends PostgreSqlIntegrationTestSupport {

	private static final UUID CART_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID BUYER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000402");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@Test
	@Order(1)
	void connectsToPostgreSqlAndWritesData() {
		String version = jdbcTemplate.queryForObject(
			"select current_setting('server_version')",
			String.class
		);
		jdbcTemplate.update("""
			insert into cart (id, buyer_id, total_amount, created_at, updated_at)
			values (?, ?, 0, current_timestamp, current_timestamp)
			""", CART_ID, BUYER_ID);

		assertThat(version).startsWith("18.4");
		assertThat(cartRowCount()).isEqualTo(1);
	}

	@Test
	@Order(2)
	void startsTheNextTestWithNoApplicationData() {
		assertThat(cartRowCount()).isZero();
	}

	private long cartRowCount() {
		Long count = jdbcTemplate.queryForObject("select count(*) from cart", Long.class);
		return count == null ? 0 : count;
	}
}
