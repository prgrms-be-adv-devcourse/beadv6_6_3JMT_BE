package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.order.domain.model.OutboxRetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "prompthub.outbox-relay.retry")
public record OutboxRetryProperties(
	@DefaultValue("5000") long initialDelayMs,
	@DefaultValue("300000") long maxDelayMs,
	@DefaultValue("10") int maxAttempts
) {

	@ConstructorBinding
	public OutboxRetryProperties {
		if (initialDelayMs <= 0) {
			throw new IllegalArgumentException("initialDelayMs must be positive");
		}
		if (maxDelayMs < initialDelayMs) {
			throw new IllegalArgumentException("maxDelayMs must not be less than initialDelayMs");
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be positive");
		}
	}

	public OutboxRetryPolicy toPolicy() {
		return new OutboxRetryPolicy(
			Duration.ofMillis(initialDelayMs),
			Duration.ofMillis(maxDelayMs),
			maxAttempts
		);
	}
}
