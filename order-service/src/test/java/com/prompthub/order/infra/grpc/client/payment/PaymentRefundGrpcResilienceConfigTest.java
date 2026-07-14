package com.prompthub.order.infra.grpc.client.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRefundGrpcResilienceConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PaymentRefundGrpcResilienceConfig.class)
		.withInitializer(context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance()))
		.withPropertyValues(
			"spring.profiles.active=prod",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.sliding-window-size=12",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.minimum-number-of-calls=6",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.failure-rate-threshold=40",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.slow-call-duration-threshold=800ms",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.slow-call-rate-threshold=45",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.wait-duration-in-open-state=20s",
			"resilience4j.circuitbreaker.configs.payment-refund-grpc-default.permitted-number-of-calls-in-half-open-state=2",
			"resilience4j.bulkhead.instances.payment-refund-grpc-bulkhead.max-concurrent-calls=8",
			"resilience4j.bulkhead.instances.payment-refund-grpc-bulkhead.max-wait-duration=0ms"
		);

	@Test
	void createsDedicatedCircuitBreakerAndBulkheadFromConfiguredValues() {
		contextRunner.run(context -> {
			PaymentRefundGrpcResilience resilience = context.getBean(PaymentRefundGrpcResilience.class);

			assertThat(resilience.circuitBreaker().getName()).isEqualTo("paymentRefundStatusGrpc");
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(12);
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(6);
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(40);
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig().getSlowCallDurationThreshold())
				.isEqualTo(Duration.ofMillis(800));
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig()
				.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(20).toMillis());
			assertThat(resilience.circuitBreaker().getCircuitBreakerConfig()
				.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
			assertThat(resilience.bulkhead().getName()).isEqualTo("paymentRefundStatusGrpcBulkhead");
			assertThat(resilience.bulkhead().getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(8);
			assertThat(resilience.bulkhead().getBulkheadConfig().getMaxWaitDuration()).isZero();
		});
	}
}
