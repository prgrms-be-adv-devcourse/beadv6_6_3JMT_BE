package com.prompthub.order.infra.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.ACQUIRE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.SUCCESS;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOrderProductReservationMetricsTest {

	@Test
	void recordsLowCardinalityReservationAndRedisMeters() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MicrometerOrderProductReservationMetrics metrics =
			new MicrometerOrderProductReservationMetrics(registry);

		metrics.recordAttempt(CONFLICT);
		metrics.recordRedis(ACQUIRE, SUCCESS, Duration.ofMillis(15));

		assertThat(registry.get("order.product.reservation.attempts")
			.tag("outcome", "conflict")
			.counter()
			.count()).isEqualTo(1);
		assertThat(registry.get("order.product.reservation.redis.duration")
			.tag("operation", "acquire")
			.tag("outcome", "success")
			.timer()
			.count()).isEqualTo(1);
		assertNoIdentifierTags(registry);
	}

	private void assertNoIdentifierTags(SimpleMeterRegistry registry) {
		assertThat(registry.getMeters())
			.flatExtracting(meter -> meter.getId().getTags())
			.extracting(Tag::getKey)
			.doesNotContain("buyerId", "productId", "orderId", "eventId");
	}
}
