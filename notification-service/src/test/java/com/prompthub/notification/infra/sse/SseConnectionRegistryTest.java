package com.prompthub.notification.infra.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.notification.application.service.NotificationItem;
import com.prompthub.notification.global.exception.NotificationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SseConnectionRegistryTest {

    @Test
    void sendsCommittedNotificationToEveryConnectionForItsRecipient() {
        SseProperties properties = new SseProperties(3, 60_000L, 30_000L);
        SseConnectionRegistry registry = new SseConnectionRegistry(properties);
        UUID recipientId = UUID.randomUUID();

        registry.connect(recipientId);
        registry.connect(recipientId);
        registry.publish(recipientId, notification(1L));

        assertThat(registry.connectionCount(recipientId)).isEqualTo(2);
    }

    @Test
    void rejectsConnectionsBeyondPerUserLimit() {
        SseProperties properties = new SseProperties(1, 60_000L, 30_000L);
        SseConnectionRegistry registry = new SseConnectionRegistry(properties);
        UUID recipientId = UUID.randomUUID();

        registry.connect(recipientId);

        assertThatThrownBy(() -> registry.connect(recipientId))
            .isInstanceOf(NotificationException.class)
            .hasMessage("알림 연결 한도를 초과했습니다.");
    }

    private NotificationItem notification(long sequence) {
        return new NotificationItem(UUID.randomUUID(), sequence, "결제 완료", "결제가 완료되었습니다.", false, Instant.now());
    }
}
