package com.prompthub.order.infra.grpc.client.product;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"default", "local", "dev", "prod", "test"})
public class ProductGrpcResilienceConfig {

	@Bean
	public ProductGrpcResilience productGrpcResilience(
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.sliding-window-size}") int slidingWindowSize,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.minimum-number-of-calls}") int minimumNumberOfCalls,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.failure-rate-threshold}") float failureRateThreshold,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-duration-threshold}") Duration slowCallDurationThreshold,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-rate-threshold}") float slowCallRateThreshold,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.wait-duration-in-open-state}") Duration waitDurationInOpenState,
		@Value("${resilience4j.circuitbreaker.configs.product-grpc-default.permitted-number-of-calls-in-half-open-state}") int permittedNumberOfCallsInHalfOpenState,
		@Value("${resilience4j.bulkhead.instances.product-grpc-bulkhead.max-concurrent-calls}") int maxConcurrentCalls,
		@Value("${resilience4j.bulkhead.instances.product-grpc-bulkhead.max-wait-duration}") Duration maxWaitDuration
	) {
		CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
			.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(slidingWindowSize)
			.minimumNumberOfCalls(minimumNumberOfCalls)
			.failureRateThreshold(failureRateThreshold)
			.slowCallDurationThreshold(slowCallDurationThreshold)
			.slowCallRateThreshold(slowCallRateThreshold)
			.waitDurationInOpenState(waitDurationInOpenState)
			.permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
			.recordException(new ProductGrpcFailurePredicate())
			.build();
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

		BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
			.maxConcurrentCalls(maxConcurrentCalls)
			.maxWaitDuration(maxWaitDuration)
			.build();
		BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);

		CircuitBreaker productOrderGrpc = circuitBreakerRegistry.circuitBreaker("productOrderGrpc");
		CircuitBreaker productQueryGrpc = circuitBreakerRegistry.circuitBreaker("productQueryGrpc");
		Bulkhead productGrpcBulkhead = bulkheadRegistry.bulkhead("productGrpcBulkhead");

		return new ProductGrpcResilience(
			productOrderGrpc,
			productQueryGrpc,
			productGrpcBulkhead,
			circuitBreakerRegistry,
			bulkheadRegistry
		);
	}

	@Bean
	public MeterBinder productGrpcCircuitBreakerMetrics(ProductGrpcResilience resilience) {
		return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(resilience.circuitBreakerRegistry());
	}

	@Bean
	public MeterBinder productGrpcBulkheadMetrics(ProductGrpcResilience resilience) {
		return TaggedBulkheadMetrics.ofBulkheadRegistry(resilience.bulkheadRegistry());
	}
}
