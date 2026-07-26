package com.prompthub.notification.application.service;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.domain.enums.NotificationType;
import com.prompthub.notification.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.notification.infra.sse.SseNotificationPublisher;
import java.time.Instant;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OrderRefundEventHandler {
    private static final String CONSUMER_GROUP = "notification-service";
    private static final String TITLE = "환불이 완료되었습니다.";
    private static final ZoneId ORDER_EVENT_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final ObjectMapper objectMapper;
    private final NotificationCommandService notificationCommandService;
    private final SseNotificationPublisher sseNotificationPublisher;

    @Transactional
    public void handle(EventMessage<JsonNode> message) {
        OrderRefundPayload payload = payload(message);
        Instant occurredAt = occurredAt(message);
        StoredNotification stored = notificationCommandService.createIfAbsent(new CreateNotificationCommand(
            message.eventId(), payload.buyerId(), NotificationType.ORDER_REFUND, TITLE,
            "주문 환불이 완료되었습니다.", "ORDER", payload.orderId(), CONSUMER_GROUP, occurredAt
        ));
        if (stored.created()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseNotificationPublisher.publish(payload.buyerId(), new NotificationItem(
                        stored.id(), stored.sequence(), NotificationType.ORDER_REFUND, TITLE, "주문 환불이 완료되었습니다.",
                        "ORDER", payload.orderId(), false, occurredAt
                    ));
                }
            });
        }
    }

    private OrderRefundPayload payload(EventMessage<JsonNode> message) {
        try {
            OrderRefundPayload payload = objectMapper.treeToValue(message.payload(), OrderRefundPayload.class);
            if (payload.orderId() == null || payload.buyerId() == null || payload.refundedAt() == null || payload.totalOrderAmount() < 0) {
                throw new IllegalArgumentException("ORDER_REFUND 필수 필드가 누락되었습니다.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("ORDER_REFUND payload를 변환할 수 없습니다.", exception);
        }
    }

    private Instant occurredAt(EventMessage<JsonNode> message) {
        if (message.eventId() == null || message.occurredAt() == null) {
            throw new IllegalArgumentException("주문 이벤트 필수 필드가 누락되었습니다.");
        }
        return message.occurredAt().atZone(ORDER_EVENT_TIME_ZONE).toInstant();
    }
}
