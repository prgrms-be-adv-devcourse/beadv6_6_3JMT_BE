package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.EventPayloadMappingException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentFailedEventHandler {

    private final ObjectMapper objectMapper;
    private final PaymentFailedProcessor paymentFailedProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentFailedPayload payload = convertPayload(message);

        paymentFailedProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }

    private PaymentFailedPayload convertPayload(EventMessage<JsonNode> message) {
        try {
            return objectMapper.treeToValue(
                    message.payload(),
                    PaymentFailedPayload.class
            );
        } catch (JacksonException e) {
            throw new EventPayloadMappingException(
                    "PAYMENT_FAILED payload mapping failed",
                    e
            );
        }
    }
}
