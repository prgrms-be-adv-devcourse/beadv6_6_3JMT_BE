package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.EventPayloadMappingException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentRefundedEventHandler {

    private final ObjectMapper objectMapper;
    private final PaymentRefundedProcessor paymentRefundedProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentRefundedPayload payload = convertPayload(message);

        paymentRefundedProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }

    private PaymentRefundedPayload convertPayload(EventMessage<JsonNode> message) {
        try {
            return objectMapper.treeToValue(
                    message.payload(),
                    PaymentRefundedPayload.class
            );
        } catch (JacksonException e) {
            throw new EventPayloadMappingException(
                    "PAYMENT_REFUNDED payload mapping failed",
                    e
            );
        }
    }
}
