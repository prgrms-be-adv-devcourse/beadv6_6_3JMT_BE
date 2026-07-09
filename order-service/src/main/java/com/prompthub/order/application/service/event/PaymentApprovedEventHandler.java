package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.EventPayloadMappingException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentApprovedEventHandler {

    private final ObjectMapper objectMapper;
    private final PaymentApprovedProcessor paymentApprovedProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentApprovedPayload payload = convertPayload(message);

        paymentApprovedProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }

    private PaymentApprovedPayload convertPayload(EventMessage<JsonNode> message) {
        try {
            return objectMapper.treeToValue(
                    message.payload(),
                    PaymentApprovedPayload.class
            );
        } catch (JacksonException e) {
            throw new EventPayloadMappingException(
                    "PAYMENT_APPROVED payload mapping failed",
                    e
            );
        }
    }
}
