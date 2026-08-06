package com.prompthub.notification.infra.realtime;

import com.prompthub.notification.application.event.NotificationCreatedEvent;
import com.prompthub.notification.application.dto.NotificationSsePayload;
import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class RedisRealtimePublisher {

    private final NotificationRepository notificationRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(NotificationCreatedEvent event) {
        notificationRepository.findByIdAndRecipientIdAndExpiresAtAfter(event.notificationId(), event.recipientId(), Instant.now())
            .ifPresent(notification -> publish(notification));
    }

    private void publish(Notification notification) {
        try {
            NotificationSsePayload payload = new NotificationSsePayload(notification.getId(), notification.getType().name(),
                notification.getTitle(), notification.getContent(),
                notificationRepository.countByRecipientIdAndReadFalseAndExpiresAtAfter(notification.getRecipientId(), Instant.now()));
            redisTemplate.convertAndSend(NotificationRedisConfig.CHANNEL,
                objectMapper.writeValueAsString(new NotificationRealtimeMessage(notification.getRecipientId(), notification.getId(), payload)));
        } catch (Exception exception) {
            log.warn("알림 Pub/Sub 발행에 실패했습니다. notificationId={}, recipientId={}, 예외유형={}",
                notification.getId(), notification.getRecipientId(), exception.getClass().getSimpleName());
        }
    }
}
