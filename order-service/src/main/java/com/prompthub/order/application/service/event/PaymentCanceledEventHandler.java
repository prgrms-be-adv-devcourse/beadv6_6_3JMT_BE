package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.EventPayloadMappingException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentCanceledPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentCanceledEventHandler {

    private final ObjectMapper objectMapper;
    private final PaymentCanceledProcessor paymentCanceledProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentCanceledPayload payload = convertPayload(message);

        paymentCanceledProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }

    private PaymentCanceledPayload convertPayload(EventMessage<JsonNode> message) {
        try {
            return objectMapper.treeToValue(
                    message.payload(),
                    PaymentCanceledPayload.class
            );
        } catch (JacksonException e) {
            throw new EventPayloadMappingException(
                    "PAYMENT_CANCELED payload mapping failed",
                    e
            );
        }
    }
}
