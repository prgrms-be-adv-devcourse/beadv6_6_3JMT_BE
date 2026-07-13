package com.prompthub.order.application.service.event.common;

import com.prompthub.common.event.EventMessage;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

public record ConsumedEventContext(
	UUID eventId,
	String eventType,
	LocalDateTime occurredAt
) {
	public static ConsumedEventContext from(EventMessage<JsonNode> message) {
		return new ConsumedEventContext(message.eventId(), message.eventType(), message.occurredAt());
	}
}
