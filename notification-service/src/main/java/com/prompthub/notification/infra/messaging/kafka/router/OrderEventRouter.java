package com.prompthub.notification.infra.messaging.kafka.router;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.application.service.OrderPaidEventHandler;
import com.prompthub.notification.application.service.OrderRefundEventHandler;
import com.prompthub.notification.infra.messaging.kafka.event.OrderEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderEventRouter {
    private final OrderPaidEventHandler paidEventHandler;
    private final OrderRefundEventHandler refundEventHandler;

    public boolean supports(String eventType) {
        return OrderEventType.from(eventType).isPresent();
    }

    public void route(EventMessage<JsonNode> message) {
        OrderEventType.from(message.eventType()).ifPresentOrElse(
            eventType -> {
                if (eventType == OrderEventType.ORDER_PAID) {
                    paidEventHandler.handle(message);
                    return;
                }
                refundEventHandler.handle(message);
            },
            () -> log.warn("Unsupported order event. eventId={}, eventType={}", message.eventId(), message.eventType())
        );
    }
}
