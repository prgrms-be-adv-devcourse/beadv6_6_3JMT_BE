package com.prompthub.order.infra.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.ACQUIRE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.SUCCESS;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.CONFLICT;
import static com.prompthub.order.fixture.OrderFixture.EVENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
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
		List<Tag> tags = registry.getMeters().stream()
			.flatMap(meter -> meter.getId().getTags().stream())
			.toList();
		assertThat(tags)
			.extracting(Tag::getKey)
			.doesNotContain("buyerId", "productId", "orderId", "eventId");
		assertThat(tags)
			.extracting(Tag::getValue)
			.doesNotContain(
				"buyerId",
				"productId",
				"orderId",
				"eventId",
				BUYER_ID.toString(),
				PRODUCT_A.toString(),
				ORDER_A.toString(),
				EVENT_ID.toString()
			);
	}
}
