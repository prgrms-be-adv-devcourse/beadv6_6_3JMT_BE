package com.prompthub.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.notification.domain.enums.NotificationType;
import com.prompthub.notification.global.exception.NotificationException;
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

    @Test
    void marksOnlyOwnersNotificationAsRead() {
        UUID ownerId = UUID.randomUUID();
        StoredNotification stored = commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), ownerId, NotificationType.ORDER_PAID, "결제 완료", "결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
        ));

        commandService.markRead(ownerId, stored.id());

        assertThatThrownBy(() -> commandService.markRead(UUID.randomUUID(), stored.id()))
            .isInstanceOf(NotificationException.class)
            .hasMessage("해당 알림에 접근할 수 없습니다.");
    }

    @Test
    void marksAllUnreadNotificationsForOneUserOnly() {
        UUID ownerId = UUID.randomUUID();
        commandService.createIfAbsent(new CreateNotificationCommand(UUID.randomUUID(), ownerId, NotificationType.ORDER_PAID, "결제 완료", "완료", "ORDER", UUID.randomUUID(), "notification-service", Instant.now()));
        commandService.createIfAbsent(new CreateNotificationCommand(UUID.randomUUID(), UUID.randomUUID(), NotificationType.ORDER_PAID, "결제 완료", "완료", "ORDER", UUID.randomUUID(), "notification-service", Instant.now()));

        assertThat(commandService.markAllRead(ownerId)).isEqualTo(1);
    }
}
