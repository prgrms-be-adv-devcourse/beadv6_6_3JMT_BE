package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationPolicy;
import com.prompthub.order.application.service.order.OrderProductIdempotencyPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "prompthub.order")
public record OrderExpirationProperties(
	@DefaultValue("true") boolean enabled,
	@DefaultValue("20") int paymentTimeoutMinutes,
	@DefaultValue("5000") long fixedDelayMs,
	@DefaultValue("100") int batchSize,
	@DefaultValue("3") int maxRetryCount,
	@DefaultValue("30") int productIdempotencyTtlMinutes
) implements OrderExpirationPolicy, OrderProductIdempotencyPolicy {

	@ConstructorBinding
	public OrderExpirationProperties {
		if (paymentTimeoutMinutes <= 0) {
			throw new IllegalArgumentException("paymentTimeoutMinutes must be positive");
		}
		if (productIdempotencyTtlMinutes <= paymentTimeoutMinutes) {
			throw new IllegalArgumentException(
				"productIdempotencyTtlMinutes must be greater than paymentTimeoutMinutes"
			);
		}
	}

	@Override
	public Duration ttl() {
		return Duration.ofMinutes(productIdempotencyTtlMinutes);
	}
}
