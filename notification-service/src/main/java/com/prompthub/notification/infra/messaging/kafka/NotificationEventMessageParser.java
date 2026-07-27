package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class NotificationEventMessageParser {

    private final ObjectMapper objectMapper;

    public EventMessage<JsonNode> parse(String rawMessage) {
        final EventMessage<JsonNode> message;
        try {
            message = objectMapper.readValue(
                rawMessage,
                new TypeReference<EventMessage<JsonNode>>() {
                }
            );
        } catch (JacksonException exception) {
            throw new NotificationEventDeserializeException(exception);
        }

        if (message == null
            || message.eventId() == null
            || message.eventType() == null
            || message.eventType().isBlank()
            || message.occurredAt() == null
            || message.aggregateType() == null
            || message.aggregateType().isBlank()
            || message.aggregateId() == null
            || message.payload() == null
            || message.payload().isNull()
            || message.payload().isMissingNode()) {
            throw new NotificationEventContractException(
                "Kafka event envelope is invalid"
            );
        }
        return message;
    }
}
