package com.prompthub.order.infra.messaging.kafka.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.outbox-relay")
public record OutboxRelayProperties(
	@DefaultValue("true") boolean enabled,
	@DefaultValue("5000") long fixedDelayMs,
	@DefaultValue("100") int batchSize,
	@DefaultValue("10000") long publishTimeoutMs,
	@DefaultValue("30000") long leaseDurationMs,
	@DefaultValue("order-events") String topic
) {

	@ConstructorBinding
	public OutboxRelayProperties {
		if (fixedDelayMs <= 0) {
			throw new IllegalArgumentException("fixedDelayMs must be positive");
		}
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
		if (publishTimeoutMs <= 0) {
			throw new IllegalArgumentException("publishTimeoutMs must be positive");
		}
		if (leaseDurationMs <= publishTimeoutMs) {
			throw new IllegalArgumentException("leaseDurationMs must be greater than publishTimeoutMs");
		}
		if (topic == null || topic.isBlank()) {
			throw new IllegalArgumentException("topic must not be blank");
		}
	}
}
