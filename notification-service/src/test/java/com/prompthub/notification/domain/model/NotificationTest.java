package com.prompthub.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.notification.domain.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    void marksUnreadNotificationAsReadWithoutChangingItsSequence() {
        Notification notification = Notification.create(
            UUID.randomUUID(),
            7L,
            UUID.randomUUID(),
            NotificationType.ORDER_PAID,
            "결제가 완료되었습니다.",
            "주문 결제가 완료되었습니다.",
            "ORDER",
            UUID.randomUUID(),
            Instant.parse("2026-07-26T08:00:00Z")
        );

        notification.markRead(Instant.parse("2026-07-26T08:01:00Z"));

        assertThat(notification.getSequence()).isEqualTo(7L);
        assertThat(notification.getReadAt()).isEqualTo(Instant.parse("2026-07-26T08:01:00Z"));
    }
}
