package com.prompthub.order.infra.messaging.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedPayload(
	UUID orderId,
	@JsonProperty("approvedAt") String approvedAtValue
) {

	public LocalDateTime approvedAt() {
		return PaymentEventTimeParser.parseRequired(approvedAtValue);
	}
}
