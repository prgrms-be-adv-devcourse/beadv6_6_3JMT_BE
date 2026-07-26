package com.prompthub.notification.infra.messaging.kafka.consumer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.infra.messaging.kafka.router.OrderEventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderEventConsumer {
    private final OrderEventRouter router;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${notification.kafka.order-topic:order-events}",
        groupId = "${notification.kafka.consumer-group:notification-service}",
        containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String rawMessage, Acknowledgment acknowledgment) {
        EventMessage<JsonNode> message = parse(rawMessage);
        if (message.eventId() == null || message.eventType() == null || message.payload() == null || message.payload().isNull()) {
            throw new IllegalArgumentException("주문 이벤트 필수 필드가 누락되었습니다.");
        }
        if (!router.supports(message.eventType())) {
            log.warn("Unsupported order event. eventId={}, eventType={}", message.eventId(), message.eventType());
            acknowledgment.acknowledge();
            return;
        }
        router.route(message);
        acknowledgment.acknowledge();
    }

    private EventMessage<JsonNode> parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, new TypeReference<EventMessage<JsonNode>>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("주문 이벤트 JSON을 읽을 수 없습니다.", exception);
        }
    }
}
