package com.prompthub.notification.infra.persistence;

import com.prompthub.notification.domain.model.Notification;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationJpaRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByRecipientIdOrderBySequenceDesc(UUID recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);
    @Modifying
    @Query("update Notification notification set notification.readAt = :readAt where notification.recipientId = :recipientId and notification.readAt is null")
    int markAllUnreadAsRead(UUID recipientId, java.time.Instant readAt);
}
