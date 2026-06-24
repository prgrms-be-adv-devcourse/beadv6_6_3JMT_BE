package com.prompthub.order.infra.messaging.kafka.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompthub.outbox-relay")
public record OutboxRelayProperties(
	boolean enabled,
	long fixedDelayMs,
	int batchSize,
	int maxRetryCount
) {
}
