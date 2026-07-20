package com.prompthub.order.infra.messaging.kafka.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedPayload(
	UUID paymentId,
	UUID orderId,
	@JsonAlias("userId") UUID buyerId,
	@JsonAlias("amount") int approvedAmount,
	@JsonProperty("approvedAt") String approvedAtValue
) {

	public UUID userId() {
		return buyerId;
	}

	public int amount() {
		return approvedAmount;
	}

	public LocalDateTime approvedAt() {
		return PaymentEventTimeParser.parseRequired(approvedAtValue);
	}
}
