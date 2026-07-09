package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompthub.order")
public record OrderExpirationProperties(
	boolean enabled,
	int paymentTimeoutMinutes,
	long fixedDelayMs,
	int batchSize,
	int maxRetryCount
) implements OrderExpirationPolicy {
}
