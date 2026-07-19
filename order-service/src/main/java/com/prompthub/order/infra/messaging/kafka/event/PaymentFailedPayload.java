package com.prompthub.order.infra.messaging.kafka.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentFailedPayload(
	UUID paymentId,
	UUID orderId,
	@JsonAlias("userId") UUID buyerId,
	String failureCode,
	String failureReason,
	@JsonProperty("failedAt") String failedAtValue
) {

	public PaymentFailedPayload(UUID paymentId, UUID orderId, UUID buyerId) {
		this(paymentId, orderId, buyerId, null, null, null);
	}

	public UUID userId() {
		return buyerId;
	}

	public LocalDateTime failedAtOr(LocalDateTime occurredAt) {
		return PaymentEventTimeParser.parseOrElse(failedAtValue, occurredAt);
	}
}
