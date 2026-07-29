package com.prompthub.notification.domain.repository;

import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.domain.enums.NotificationCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, NotificationRepositoryCustom {
    Page<Notification> findByRecipientIdAndExpiresAtAfter(UUID recipientId, Instant now, Pageable pageable);
    Page<Notification> findByRecipientIdAndCategoryAndExpiresAtAfter(UUID recipientId, NotificationCategory category, Instant now, Pageable pageable);
    long countByRecipientIdAndReadFalseAndExpiresAtAfter(UUID recipientId, Instant now);
    Optional<Notification> findByIdAndRecipientIdAndExpiresAtAfter(UUID id, UUID recipientId, Instant now);
    long deleteByExpiresAtBefore(Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from Notification n
        where n.recipientId = :recipientId
          and n.expiresAt > :now
        """)
    int deleteAllActiveByRecipientId(
        @Param("recipientId") UUID recipientId,
        @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Notification n set n.read = true, n.readAt = :readAt
        where n.recipientId = :recipientId and n.read = false and n.expiresAt > :now
        """)
    int markAllUnreadAsRead(@Param("recipientId") UUID recipientId, @Param("now") Instant now, @Param("readAt") Instant readAt);

}
