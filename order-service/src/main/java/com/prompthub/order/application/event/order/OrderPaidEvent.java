package com.prompthub.order.application.event.order;

import java.util.UUID;

public record OrderPaidEvent(UUID orderId) {
}
