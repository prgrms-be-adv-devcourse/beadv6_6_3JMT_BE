package com.prompthub.order.infra.grpc.client.payment;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile({"dev", "prod"})
public class PaymentRefundGrpcResilienceConfig {

	@Bean
	public PaymentRefundGrpcResilience paymentRefundGrpcResilience(
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.sliding-window-size}") int slidingWindowSize,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.minimum-number-of-calls}") int minimumNumberOfCalls,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.failure-rate-threshold}") float failureRateThreshold,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.slow-call-duration-threshold}") Duration slowCallDurationThreshold,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.slow-call-rate-threshold}") float slowCallRateThreshold,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.wait-duration-in-open-state}") Duration waitDurationInOpenState,
		@Value("${resilience4j.circuitbreaker.configs.payment-refund-grpc-default.permitted-number-of-calls-in-half-open-state}") int permittedNumberOfCallsInHalfOpenState,
		@Value("${resilience4j.bulkhead.instances.payment-refund-grpc-bulkhead.max-concurrent-calls}") int maxConcurrentCalls,
		@Value("${resilience4j.bulkhead.instances.payment-refund-grpc-bulkhead.max-wait-duration}") Duration maxWaitDuration
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
			.recordException(new PaymentRefundGrpcFailurePredicate())
			.build();
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

		BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
			.maxConcurrentCalls(maxConcurrentCalls)
			.maxWaitDuration(maxWaitDuration)
			.build();
		BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);

		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentRefundStatusGrpc");
		Bulkhead bulkhead = bulkheadRegistry.bulkhead("paymentRefundStatusGrpcBulkhead");
		return new PaymentRefundGrpcResilience(
			circuitBreaker,
			bulkhead,
			circuitBreakerRegistry,
			bulkheadRegistry
		);
	}

	@Bean
	public MeterBinder paymentRefundGrpcCircuitBreakerMetrics(PaymentRefundGrpcResilience resilience) {
		return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(resilience.circuitBreakerRegistry());
	}

	@Bean
	public MeterBinder paymentRefundGrpcBulkheadMetrics(PaymentRefundGrpcResilience resilience) {
		return TaggedBulkheadMetrics.ofBulkheadRegistry(resilience.bulkheadRegistry());
	}
}
