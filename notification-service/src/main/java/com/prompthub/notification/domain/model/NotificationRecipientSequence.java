package com.prompthub.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "notification_recipient_sequence")
@NoArgsConstructor(access = PROTECTED)
public class NotificationRecipientSequence {
    @Id
    @Column(name = "recipient_id", columnDefinition = "uuid")
    private UUID recipientId;
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    public NotificationRecipientSequence(UUID recipientId) {
        this.recipientId = recipientId;
    }

    public long next() {
        return ++lastSequence;
    }
}
