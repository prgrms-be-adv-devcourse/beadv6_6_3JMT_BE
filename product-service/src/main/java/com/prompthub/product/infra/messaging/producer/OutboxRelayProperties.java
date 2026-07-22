package com.prompthub.product.infra.messaging.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.outbox-relay")
public record OutboxRelayProperties(
	boolean enabled,
	long fixedDelayMs,
	int batchSize,
	int maxRetryCount,
	@DefaultValue("product-events") String topic
) {
}
