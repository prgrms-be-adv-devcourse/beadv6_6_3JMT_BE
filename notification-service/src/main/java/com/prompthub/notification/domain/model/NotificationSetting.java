package com.prompthub.notification.domain.model;

import static lombok.AccessLevel.PROTECTED;

import com.prompthub.notification.domain.enums.NotificationCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "notification_setting",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_setting_recipient_category",
        columnNames = {"recipient_id", "category"}
    )
)
@NoArgsConstructor(access = PROTECTED)
public class NotificationSetting {

    @Id
    @Column(name = "setting_id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recipient_id", columnDefinition = "uuid", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, nullable = false)
    private NotificationCategory category;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
