package com.prompthub.notification.domain.model;

import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    @Test
    void markRead_marksUnreadNotificationOnlyOnce() {
        Instant createdAt = Instant.parse("2026-07-27T00:00:00Z");
        Notification notification = Notification.create(
            UUID.randomUUID(),
            NotificationType.ORDER_PAID,
            NotificationCategory.PAYMENT,
            "결제가 완료되었습니다.",
            "ORD-1 주문의 결제가 완료되었습니다.",
            "/mypage?tab=payments",
            "ORDER",
            UUID.randomUUID(),
            createdAt,
            "order-paid:event-1"
        );

        Instant firstReadAt = Instant.parse("2026-07-27T01:00:00Z");
        notification.markRead(firstReadAt);
        notification.markRead(Instant.parse("2026-07-27T02:00:00Z"));

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void create_setsNinetyDayRetentionAndExpiresAtBoundary() {
        Instant createdAt = Instant.parse("2026-07-27T00:00:00Z");
        Notification notification = Notification.create(
            UUID.randomUUID(), NotificationType.ORDER_CREATED, NotificationCategory.ORDER,
            "주문이 생성되었습니다.", "ORD-1 주문이 생성되었습니다.", "/mypage?tab=payments",
            "ORDER", UUID.randomUUID(), createdAt, createdAt, "order-created:event-1"
        );

        assertThat(notification.getExpiresAt()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(notification.isExpired(Instant.parse("2026-10-24T23:59:59Z"))).isFalse();
        assertThat(notification.isExpired(Instant.parse("2026-10-25T00:00:00Z"))).isTrue();
    }
}
