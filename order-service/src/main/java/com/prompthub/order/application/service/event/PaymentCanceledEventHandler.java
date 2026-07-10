package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentCanceledPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentCanceledEventHandler {

    private final EventPayloadMapper eventPayloadMapper;
    private final PaymentCanceledProcessor paymentCanceledProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentCanceledPayload payload = eventPayloadMapper.convert(message, PaymentCanceledPayload.class);

        paymentCanceledProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }
}
