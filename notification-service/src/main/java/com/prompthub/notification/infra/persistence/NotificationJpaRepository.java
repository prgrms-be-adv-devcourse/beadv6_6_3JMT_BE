package com.prompthub.notification.infra.persistence;

import com.prompthub.notification.domain.model.Notification;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationJpaRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByRecipientIdOrderBySequenceDesc(UUID recipientId, Pageable pageable);
    List<Notification> findByRecipientIdAndSequenceGreaterThanOrderBySequenceAsc(UUID recipientId, long sequence);
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);
    @Modifying
    @Query("update Notification notification set notification.readAt = :readAt where notification.recipientId = :recipientId and notification.readAt is null")
    int markAllUnreadAsRead(UUID recipientId, java.time.Instant readAt);
}
