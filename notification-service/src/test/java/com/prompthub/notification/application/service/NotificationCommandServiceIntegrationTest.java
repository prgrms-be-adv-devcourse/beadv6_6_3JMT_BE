package com.prompthub.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.notification.domain.enums.NotificationType;
import com.prompthub.notification.global.exception.NotificationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
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

    @Test
    void assignsDistinctSequencesWhenFirstNotificationsArriveConcurrently() throws Exception {
        UUID recipientId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<StoredNotification>> results = List.of(
                executor.submit(() -> createAfterStart(ready, start, recipientId)),
                executor.submit(() -> createAfterStart(ready, start, recipientId))
            );
            ready.await();
            start.countDown();

            assertThat(results.stream().map(this::get).map(StoredNotification::sequence))
                .containsExactlyInAnyOrder(1L, 2L);
        } finally {
            executor.shutdownNow();
        }
    }

    private StoredNotification createAfterStart(CountDownLatch ready, CountDownLatch start, UUID recipientId) {
        ready.countDown();
        try {
            start.await();
            return commandService.createIfAbsent(new CreateNotificationCommand(
                UUID.randomUUID(), recipientId, NotificationType.ORDER_PAID, "결제 완료", "완료",
                "ORDER", UUID.randomUUID(), "notification-service", Instant.now()
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private StoredNotification get(Future<StoredNotification> result) {
        try {
            return result.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
