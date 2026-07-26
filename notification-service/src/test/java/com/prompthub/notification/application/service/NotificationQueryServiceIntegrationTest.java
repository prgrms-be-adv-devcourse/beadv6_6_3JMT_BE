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
class NotificationQueryServiceIntegrationTest {

    @Autowired
    private NotificationCommandService commandService;

    @Autowired
    private NotificationQueryService queryService;

    @Test
    void returnsOnlyTheAuthenticatedUsersUnreadNotifications() {
        UUID ownerId = UUID.randomUUID();
        create(ownerId);
        create(UUID.randomUUID());

        NotificationPage page = queryService.findPage(ownerId, 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(queryService.countUnread(ownerId)).isEqualTo(1L);
    }

    private void create(UUID recipientId) {
        commandService.createIfAbsent(new CreateNotificationCommand(
            UUID.randomUUID(), recipientId, NotificationType.ORDER_PAID,
            "결제가 완료되었습니다.", "주문 결제가 완료되었습니다.",
            "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
        ));
    }
}
