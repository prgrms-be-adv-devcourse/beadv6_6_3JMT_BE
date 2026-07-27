package com.prompthub.notification.domain.repository;

import com.prompthub.notification.domain.model.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepositoryCustom {
    List<Notification> findReplayCandidates(UUID recipientId, Instant now, Instant cursorCreatedAt, UUID cursorId, int limit);
}
