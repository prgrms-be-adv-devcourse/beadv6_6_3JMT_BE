package com.prompthub.order.support;

import java.time.LocalDateTime;
import java.util.UUID;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisContainerSupportSmokeTest extends RedisContainerSupport {

	private static final UUID ORDER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000401");

	@Autowired
	private OrderExpirationStore orderExpirationStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private ProductClient productClient;

	@Test
	@Order(1)
	void connectsToRedisAndWritesOrderExpirationData() {
		orderExpirationStore.registerExpiration(ORDER_ID, LocalDateTime.now().minusMinutes(30), 20);
		orderExpirationStore.incrementRetryCount(ORDER_ID);
		orderExpirationStore.moveToDeadLetter(ORDER_ID);

		assertThat(redisTemplate.opsForZSet().score("order:expiration", ORDER_ID.toString()))
			.isNotNull();
		assertThat(redisTemplate.opsForHash().get("order:expiration:retry", ORDER_ID.toString()))
			.isEqualTo("1");
		assertThat(redisTemplate.opsForList().range("order:expiration:dlq", 0, -1))
			.containsExactly(ORDER_ID.toString());
	}

	@Test
	@Order(2)
	void startsTheNextTestWithNoOrderData() {
		assertThat(redisTemplate.keys("order:*")).isEmpty();
	}
}
