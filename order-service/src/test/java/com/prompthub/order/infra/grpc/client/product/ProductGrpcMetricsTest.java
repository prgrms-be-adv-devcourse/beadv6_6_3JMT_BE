package com.prompthub.order.infra.grpc.client.product;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductGrpcMetricsTest {

	@Test
	void bindsCircuitBreakerAndSharedBulkheadMetersWithInstanceNames() {
		ProductGrpcResilienceConfig config = new ProductGrpcResilienceConfig();
		ProductGrpcResilience resilience = config.productGrpcResilience(
			20, 10, 50, Duration.ofMillis(700), 50, Duration.ofSeconds(30), 3, 20, Duration.ZERO
		);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

		config.productGrpcCircuitBreakerMetrics(resilience).bindTo(meterRegistry);
		config.productGrpcBulkheadMetrics(resilience).bindTo(meterRegistry);

		Set<String> circuitBreakerNames = meterRegistry.getMeters().stream()
			.map(Meter::getId)
			.filter(id -> id.getName().startsWith("resilience4j.circuitbreaker"))
			.map(id -> id.getTag("name"))
			.collect(java.util.stream.Collectors.toSet());
		Set<String> bulkheadNames = meterRegistry.getMeters().stream()
			.map(Meter::getId)
			.filter(id -> id.getName().startsWith("resilience4j.bulkhead"))
			.map(id -> id.getTag("name"))
			.collect(java.util.stream.Collectors.toSet());

		assertThat(circuitBreakerNames).containsExactlyInAnyOrder("productOrderGrpc", "productQueryGrpc");
		assertThat(bulkheadNames).containsExactly("productGrpcBulkhead");
	}
}
