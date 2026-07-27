package com.prompthub.notification.domain.model;

import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
    name = "notification",
    indexes = {
        @Index(name = "idx_notification_recipient_created_at", columnList = "recipient_id, created_at"),
        @Index(name = "idx_notification_recipient_read_created_at", columnList = "recipient_id, is_read, created_at"),
        @Index(name = "idx_notification_expires_at", columnList = "expires_at")
    }
)
@NoArgsConstructor(access = PROTECTED)
public class Notification {

    @Id
    @Column(name = "notification_id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recipient_id", columnDefinition = "uuid", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 50, nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, nullable = false)
    private NotificationCategory category;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", length = 1000, nullable = false)
    private String content;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id", columnDefinition = "uuid")
    private UUID referenceId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deduplication_key", length = 200, nullable = false, unique = true)
    private String deduplicationKey;

    private Notification(
        UUID recipientId,
        NotificationType type,
        NotificationCategory category,
        String title,
        String content,
        String linkUrl,
        String referenceType,
        UUID referenceId,
        Instant occurredAt,
        Instant createdAt,
        String deduplicationKey
    ) {
        this.id = UUID.randomUUID();
        this.recipientId = recipientId;
        this.type = type;
        this.category = category;
        this.title = title;
        this.content = content;
        this.linkUrl = linkUrl;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.read = false;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plus(90, ChronoUnit.DAYS);
        this.deduplicationKey = deduplicationKey;
    }

    public static Notification create(
        UUID recipientId,
        NotificationType type,
        NotificationCategory category,
        String title,
        String content,
        String linkUrl,
        String referenceType,
        UUID referenceId,
        Instant occurredAt,
        String deduplicationKey
    ) {
        return new Notification(
            recipientId, type, category, title, content, linkUrl, referenceType, referenceId,
            occurredAt, Instant.now(), deduplicationKey
        );
    }

    public static Notification create(
        UUID recipientId,
        NotificationType type,
        NotificationCategory category,
        String title,
        String content,
        String linkUrl,
        String referenceType,
        UUID referenceId,
        Instant occurredAt,
        Instant createdAt,
        String deduplicationKey
    ) {
        return new Notification(
            recipientId, type, category, title, content, linkUrl, referenceType, referenceId,
            occurredAt, createdAt, deduplicationKey
        );
    }

    public void markRead(Instant readAt) {
        if (!read) {
            this.read = true;
            this.readAt = readAt;
        }
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

}
