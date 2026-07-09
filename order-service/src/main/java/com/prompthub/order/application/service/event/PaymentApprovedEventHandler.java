package com.prompthub.order.application.service.event;

import tools.jackson.databind.JsonNode;
import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentApprovedEventHandler {

    private final EventPayloadMapper eventPayloadMapper;
    private final PaymentApprovedProcessor paymentApprovedProcessor;

    public void handle(EventMessage<JsonNode> message) {
        PaymentApprovedPayload payload = eventPayloadMapper.convert(message, PaymentApprovedPayload.class);

        paymentApprovedProcessor.process(
                message.eventId(),
                message.eventType(),
                message.occurredAt(),
                payload
        );
    }
}
