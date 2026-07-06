package com.prompthub.order.application.event.order;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderCreatedEvent(
	UUID orderId,
	LocalDateTime createdAt
) {
}
