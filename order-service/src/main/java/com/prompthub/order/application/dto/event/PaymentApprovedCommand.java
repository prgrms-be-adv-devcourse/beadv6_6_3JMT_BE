package com.prompthub.order.application.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedCommand(
	UUID orderId,
	int approvedAmount,
	LocalDateTime approvedAt
) {
}
