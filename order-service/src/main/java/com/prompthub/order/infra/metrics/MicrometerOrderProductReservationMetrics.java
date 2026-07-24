package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.order.OrderProductReservationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class MicrometerOrderProductReservationMetrics implements OrderProductReservationMetrics {

	private final MeterRegistry meterRegistry;

	@Override
	public void recordAttempt(ReservationOutcome outcome) {
		meterRegistry.counter(
			"order.product.reservation.attempts",
			"outcome",
			tag(outcome)
		).increment();
	}

	@Override
	public void recordRedis(
		RedisOperation operation,
		RedisOutcome outcome,
		Duration duration
	) {
		Timer.builder("order.product.reservation.redis.duration")
			.tag("operation", tag(operation))
			.tag("outcome", tag(outcome))
			.register(meterRegistry)
			.record(duration);
	}

	private String tag(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
