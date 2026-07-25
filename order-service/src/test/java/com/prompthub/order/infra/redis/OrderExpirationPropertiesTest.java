package com.prompthub.order.infra.redis;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExpirationPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void defaultsProductIdempotencyTtlToThirtyMinutes() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OrderExpirationProperties.class).ttl())
				.isEqualTo(Duration.ofMinutes(30));
		});
	}

	@Test
	void rejectsTtlNotGreaterThanPaymentTimeout() {
		contextRunner
			.withPropertyValues(
				"prompthub.order.payment-timeout-minutes=20",
				"prompthub.order.product-idempotency-ttl-minutes=20"
			)
			.run(context -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OrderExpirationProperties.class)
	static class PropertiesConfiguration {
	}
}
