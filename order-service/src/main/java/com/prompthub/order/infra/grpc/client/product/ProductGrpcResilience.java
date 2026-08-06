package com.prompthub.order.infra.grpc.client.product;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

public record ProductGrpcResilience(
	CircuitBreaker productOrderGrpc,
	CircuitBreaker productQueryGrpc,
	Bulkhead productGrpcBulkhead,
	CircuitBreakerRegistry circuitBreakerRegistry,
	BulkheadRegistry bulkheadRegistry
) {

	public CircuitBreaker circuitBreaker(String name) {
		return switch (name) {
			case "productOrderGrpc" -> productOrderGrpc;
			case "productQueryGrpc" -> productQueryGrpc;
			default -> throw new IllegalArgumentException("Unsupported Product gRPC Circuit Breaker: " + name);
		};
	}
}
