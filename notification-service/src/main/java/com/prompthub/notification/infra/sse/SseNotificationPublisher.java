package com.prompthub.notification.infra.sse;

import com.prompthub.notification.application.service.NotificationItem;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SseNotificationPublisher {
    private final SseConnectionRegistry connectionRegistry;

    public void publish(UUID recipientId, NotificationItem notification) {
        connectionRegistry.publish(recipientId, notification);
    }
}
