package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.order.OrderExpirationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class MicrometerOrderExpirationMetrics implements OrderExpirationMetrics {

	private final MeterRegistry meterRegistry;

	@Override
	public void recordCandidates(CandidateSource source, int count) {
		if (count <= 0) {
			return;
		}
		meterRegistry.counter(
			"order.expiration.candidates",
			"source",
			tag(source)
		).increment(count);
	}

	@Override
	public void recordCompensation(CompensationOutcome outcome) {
		meterRegistry.counter(
			"order.expiration.compensation",
			"outcome",
			tag(outcome)
		).increment();
	}

	private String tag(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
