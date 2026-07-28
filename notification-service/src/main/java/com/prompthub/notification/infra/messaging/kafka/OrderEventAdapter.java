package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class OrderEventAdapter {

    public OrderEvent adapt(EventMessage<JsonNode> message) {
        JsonNode payload = message.payload();
        return new OrderEvent(
            message.eventId(),
            message.eventType(),
            requiredUuid(payload, "orderId"),
            requiredUuid(payload, "buyerId"),
            requiredText(payload, "orderNumber"),
            message.occurredAt().toInstant(ZoneOffset.UTC),
            payload
        );
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(requiredText(payload, field));
        } catch (IllegalArgumentException exception) {
            throw new NotificationEventContractException("Invalid UUID field: " + field);
        }
    }

    private String requiredText(JsonNode payload, String field) {
        if (payload == null || payload.isNull()) {
            throw new NotificationEventContractException("Kafka event payload is invalid");
        }
        String value = payload.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new NotificationEventContractException("Missing required payload field: " + field);
        }
        return value;
    }

    public record OrderEvent(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID buyerId,
        String orderNumber,
        Instant occurredAt,
        JsonNode payload
    ) {
    }
}
