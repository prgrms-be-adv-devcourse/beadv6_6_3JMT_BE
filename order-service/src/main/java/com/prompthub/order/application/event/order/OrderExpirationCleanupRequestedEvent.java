package com.prompthub.order.application.event.order;

import java.util.UUID;

public record OrderExpirationCleanupRequestedEvent(UUID orderId) {
}
