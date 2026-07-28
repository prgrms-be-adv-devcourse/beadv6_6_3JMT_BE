package com.prompthub.notification.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.infra.persistence.config.QuerydslConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(QuerydslConfig.class)
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository repository;

    @Test
    void deleteAllActiveByRecipientId_deletesOnlyActiveOwnedRows() {
        UUID recipientId = UUID.randomUUID();
        UUID otherRecipientId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-28T05:00:00Z");
        Notification activeOwned = notification(
            recipientId,
            now.minus(1, ChronoUnit.DAYS),
            "active-owned"
        );
        Notification expiredOwned = notification(
            recipientId,
            now.minus(91, ChronoUnit.DAYS),
            "expired-owned"
        );
        Notification activeOther = notification(
            otherRecipientId,
            now.minus(1, ChronoUnit.DAYS),
            "active-other"
        );
        repository.saveAllAndFlush(List.of(activeOwned, expiredOwned, activeOther));

        int deleted = repository.deleteAllActiveByRecipientId(recipientId, now);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(activeOwned.getId())).isEmpty();
        assertThat(repository.findById(expiredOwned.getId())).isPresent();
        assertThat(repository.findById(activeOther.getId())).isPresent();
    }

    @Test
    void deleteAllActiveByRecipientId_withoutTargetsReturnsZero() {
        assertThat(repository.deleteAllActiveByRecipientId(
            UUID.randomUUID(),
            Instant.parse("2026-07-28T05:00:00Z")
        )).isZero();
    }

    private Notification notification(
        UUID recipientId,
        Instant createdAt,
        String deduplicationKey
    ) {
        return Notification.create(
            recipientId,
            NotificationType.ORDER_CREATED,
            NotificationCategory.ORDER,
            "제목",
            "내용",
            "/mypage",
            "ORDER",
            UUID.randomUUID(),
            createdAt,
            createdAt,
            deduplicationKey
        );
    }
}
