package com.prompthub.order.infra.messaging.kafka.router;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.PaymentApprovedEventHandler;
import com.prompthub.order.application.service.event.PaymentFailedEventHandler;
import com.prompthub.order.application.service.event.PaymentRefundedEventHandler;
import com.prompthub.order.infra.messaging.kafka.event.PaymentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventRouter {

    private final PaymentApprovedEventHandler approvedHandler;
    private final PaymentRefundedEventHandler refundedHandler;
    private final PaymentFailedEventHandler failedHandler;

    public void route(EventMessage<JsonNode> message) {
        PaymentEventType eventType = PaymentEventType.from(message.eventType());

        if (eventType == null) {
            log.warn("Unsupported payment event. eventId={}, eventType={}",
                    message.eventId(),
                    message.eventType());
            return;
        }

        switch (eventType) {
            case PAYMENT_APPROVED -> approvedHandler.handle(message);
            case PAYMENT_REFUNDED -> refundedHandler.handle(message);
            case PAYMENT_FAILED -> failedHandler.handle(message);
        }
    }
}
