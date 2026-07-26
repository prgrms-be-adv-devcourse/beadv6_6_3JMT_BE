package com.prompthub.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.notification.domain.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationCommandServiceIntegrationTest {

    @Autowired
    private NotificationCommandService commandService;

    @Test
    void duplicateEventCreatesOneNotificationWithOneSequence() {
        UUID eventId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        CreateNotificationCommand command = new CreateNotificationCommand(
            eventId, recipientId, NotificationType.ORDER_PAID,
            "결제가 완료되었습니다.", "주문 결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.parse("2026-07-26T08:00:00Z")
        );

        StoredNotification first = commandService.createIfAbsent(command);
        StoredNotification second = commandService.createIfAbsent(command);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.sequence()).isEqualTo(first.sequence());
        assertThat(first.sequence()).isEqualTo(1L);
    }
}
