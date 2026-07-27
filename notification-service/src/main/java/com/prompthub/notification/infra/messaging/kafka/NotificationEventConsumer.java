package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "ORDER_CREATED", "ORDER_PAID", "ORDER_PAYMENT_FAILED", "ORDER_EXPIRED",
        "ORDER_REFUND_REQUESTED", "ORDER_REFUND", "ORDER_REFUND_FAILED"
    );
    private final NotificationEventMessageParser messageParser;
    private final OrderEventHandler orderEventHandler;

    @KafkaListener(
        topics = "${prompthub.notification.kafka.order-events-topic:order-events}",
        containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(String rawMessage, Acknowledgment acknowledgment) {
        EventMessage<JsonNode> message = messageParser.parse(rawMessage);
        if (!SUPPORTED_EVENT_TYPES.contains(message.eventType())) {
            acknowledgment.acknowledge();
            return;
        }
        orderEventHandler.handle(message);
        acknowledgment.acknowledge();
    }
}
