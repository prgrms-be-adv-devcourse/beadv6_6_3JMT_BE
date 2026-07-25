package com.prompthub.order.application.service.order;

import java.time.Duration;

public interface OrderProductIdempotencyPolicy {
	Duration ttl();
}
