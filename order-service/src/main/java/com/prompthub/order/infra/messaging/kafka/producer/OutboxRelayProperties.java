package com.prompthub.order.infra.messaging.kafka.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.outbox-relay")
public record OutboxRelayProperties(
	boolean enabled,
	long fixedDelayMs,
	int batchSize,
	int maxRetryCount,
	@DefaultValue("order-events") String topic
) {
}
