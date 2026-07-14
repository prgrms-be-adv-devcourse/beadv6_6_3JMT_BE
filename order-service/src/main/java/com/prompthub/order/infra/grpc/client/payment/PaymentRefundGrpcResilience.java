package com.prompthub.order.infra.grpc.client.payment;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

public record PaymentRefundGrpcResilience(
	CircuitBreaker circuitBreaker,
	Bulkhead bulkhead,
	CircuitBreakerRegistry circuitBreakerRegistry,
	BulkheadRegistry bulkheadRegistry
) {
}
