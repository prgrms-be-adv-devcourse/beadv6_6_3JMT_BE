package com.prompthub.notification.infra.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "prompthub.notification.redis-pubsub", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisSubscriber implements MessageListener {
    private final ObjectMapper objectMapper;
    private final SseConnectionRegistry connectionRegistry;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            NotificationRealtimeMessage realtimeMessage = objectMapper.readValue(message.getBody(), NotificationRealtimeMessage.class);
            connectionRegistry.send(realtimeMessage.recipientId(), realtimeMessage.notificationId(), realtimeMessage.payload());
        } catch (Exception exception) {
            log.warn("알림 Pub/Sub 이벤트 역직렬화에 실패했습니다. 예외유형={}", exception.getClass().getSimpleName());
        }
    }
}
