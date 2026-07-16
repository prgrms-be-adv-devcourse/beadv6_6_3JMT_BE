package com.prompthub.order.infra.messaging.kafka.event;

import java.util.List;
import java.util.UUID;

public record PaymentApprovedOrderPayload(
	UUID orderId,
	List<UUID> orderProductIds
) {
}
