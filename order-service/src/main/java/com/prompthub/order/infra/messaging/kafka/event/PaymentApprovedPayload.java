package com.prompthub.order.infra.messaging.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedPayload(
	UUID orderId,
	Integer approvedAmount,
	@JsonProperty("approvedAt") String approvedAtValue
) {

	public PaymentApprovedPayload(UUID orderId, String approvedAtValue) {
		this(orderId, null, approvedAtValue);
	}

	public LocalDateTime approvedAt() {
		return PaymentEventTimeParser.parseOrNull(approvedAtValue);
	}
}
