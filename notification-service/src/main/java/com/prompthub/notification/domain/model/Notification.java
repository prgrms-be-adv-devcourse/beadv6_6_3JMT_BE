package com.prompthub.notification.domain.model;

import com.prompthub.notification.domain.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = PROTECTED)
public class Notification {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recipient_id", nullable = false, columnDefinition = "uuid")
    private UUID recipientId;

    @Column(nullable = false)
    private long sequence;

    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, columnDefinition = "uuid")
    private UUID referenceId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Notification(
        UUID recipientId,
        long sequence,
        UUID eventId,
        NotificationType type,
        String title,
        String message,
        String referenceType,
        UUID referenceId,
        Instant createdAt
    ) {
        this.id = UUID.randomUUID();
        this.recipientId = recipientId;
        this.sequence = sequence;
        this.eventId = eventId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }

    public static Notification create(
        UUID recipientId,
        long sequence,
        UUID eventId,
        NotificationType type,
        String title,
        String message,
        String referenceType,
        UUID referenceId,
        Instant createdAt
    ) {
        return new Notification(recipientId, sequence, eventId, type, title, message, referenceType, referenceId, createdAt);
    }

    public void markRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
