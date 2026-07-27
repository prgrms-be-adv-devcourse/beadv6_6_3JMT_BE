package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.application.service.NotificationService;
import com.prompthub.notification.domain.repository.NotificationProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderEventHandler {

    private static final String CONSUMER_GROUP = "notification-service";
    private final OrderEventAdapter orderEventAdapter;
    private final OrderNotificationTemplateFactory templateFactory;
    private final NotificationService notificationService;
    private final NotificationProcessedEventRepository processedEventRepository;

    @Transactional
    public void handle(EventMessage<JsonNode> message) {
        if (processedEventRepository.claim(
            UUID.randomUUID(), message.eventId(), CONSUMER_GROUP, message.eventType(), Instant.now()
        ) == 0) {
            return;
        }
        OrderEventAdapter.OrderEvent event = orderEventAdapter.adapt(message);
        notificationService.createNotification(templateFactory.create(event));
    }
}
