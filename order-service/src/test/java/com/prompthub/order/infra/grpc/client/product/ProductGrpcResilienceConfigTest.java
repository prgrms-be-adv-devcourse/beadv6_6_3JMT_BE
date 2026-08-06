package com.prompthub.order.infra.grpc.client.product;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ProductGrpcResilienceConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ProductGrpcResilienceConfigTest {

	private final ApplicationContextRunner productionContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(ProductGrpcResilienceConfig.class)
		.withInitializer(context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance()))
		.withPropertyValues(
			"spring.profiles.active=prod",
			"resilience4j.circuitbreaker.configs.product-grpc-default.sliding-window-size=20",
			"resilience4j.circuitbreaker.configs.product-grpc-default.minimum-number-of-calls=10",
			"resilience4j.circuitbreaker.configs.product-grpc-default.failure-rate-threshold=50",
			"resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-duration-threshold=700ms",
			"resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-rate-threshold=50",
			"resilience4j.circuitbreaker.configs.product-grpc-default.wait-duration-in-open-state=30s",
			"resilience4j.circuitbreaker.configs.product-grpc-default.permitted-number-of-calls-in-half-open-state=3",
			"resilience4j.bulkhead.instances.product-grpc-bulkhead.max-concurrent-calls=20",
			"resilience4j.bulkhead.instances.product-grpc-bulkhead.max-wait-duration=0ms"
		);

	@Autowired
	private ProductGrpcResilience resilience;

	@Test
	void createsSeparatedCircuitBreakersAndSharedBulkheadFromConfiguredValues() {
		assertThat(resilience.productOrderGrpc().getName()).isEqualTo("productOrderGrpc");
		assertThat(resilience.productQueryGrpc().getName()).isEqualTo("productQueryGrpc");
		assertThat(resilience.productOrderGrpc()).isNotSameAs(resilience.productQueryGrpc());
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50);
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getSlowCallDurationThreshold())
			.isEqualTo(java.time.Duration.ofMillis(700));
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
			.isEqualTo(java.time.Duration.ofMillis(100).toMillis());
		assertThat(resilience.productOrderGrpc().getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
			.isEqualTo(3);
		assertThat(resilience.productGrpcBulkhead().getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
		assertThat(resilience.productGrpcBulkhead().getBulkheadConfig().getMaxWaitDuration()).isZero();
	}

	@Test
	void bindsProductionOpenWaitDurationToThirtySeconds() {
		productionContextRunner.run(context -> {
			ProductGrpcResilience productionResilience = context.getBean(ProductGrpcResilience.class);

			assertThat(productionResilience.productOrderGrpc().getCircuitBreakerConfig()
				.getWaitIntervalFunctionInOpenState().apply(1))
				.isEqualTo(Duration.ofSeconds(30).toMillis());
		});
	}
}
