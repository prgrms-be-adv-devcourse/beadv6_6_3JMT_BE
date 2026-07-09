package com.prompthub.order.application.service.event;

import tools.jackson.databind.JsonNode;
import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFailedEventHandler {

    private final EventPayloadMapper eventPayloadMapper;
    private final PaymentFailedProcessor paymentFailedProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentFailedPayload payload = eventPayloadMapper.convert(message, PaymentFailedPayload.class);

        paymentFailedProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }
}
