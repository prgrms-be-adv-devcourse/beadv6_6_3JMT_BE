package com.prompthub.order.infra.grpc.client.payment;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

final class TestResilienceFactory {

	private TestResilienceFactory() {
	}

	static PaymentRefundGrpcResilience create(int maxConcurrentCalls) {
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
			.slidingWindowSize(2)
			.minimumNumberOfCalls(2)
			.failureRateThreshold(50)
			.recordException(new PaymentRefundGrpcFailurePredicate())
			.build());
		BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.custom()
			.maxConcurrentCalls(maxConcurrentCalls)
			.maxWaitDuration(Duration.ZERO)
			.build());
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentRefundStatusGrpc");
		Bulkhead bulkhead = bulkheadRegistry.bulkhead("paymentRefundStatusGrpcBulkhead");
		return new PaymentRefundGrpcResilience(circuitBreaker, bulkhead, circuitBreakerRegistry, bulkheadRegistry);
	}
}
