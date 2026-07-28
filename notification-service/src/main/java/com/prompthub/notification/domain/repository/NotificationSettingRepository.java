package com.prompthub.notification.domain.repository;

import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.model.NotificationSetting;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSettingRepository
    extends JpaRepository<NotificationSetting, UUID> {

    List<NotificationSetting> findAllByRecipientId(UUID recipientId);

    Optional<NotificationSetting> findByRecipientIdAndCategory(
        UUID recipientId,
        NotificationCategory category
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO notification_setting (
            setting_id, recipient_id, category, enabled, created_at, updated_at
        ) VALUES (
            :settingId, :recipientId, :category, :enabled, :now, :now
        )
        ON CONFLICT (recipient_id, category)
        DO UPDATE SET
            enabled = EXCLUDED.enabled,
            updated_at = CASE
                WHEN notification_setting.enabled = EXCLUDED.enabled
                    THEN notification_setting.updated_at
                ELSE EXCLUDED.updated_at
            END
        """, nativeQuery = true)
    int upsert(
        @Param("settingId") UUID settingId,
        @Param("recipientId") UUID recipientId,
        @Param("category") String category,
        @Param("enabled") boolean enabled,
        @Param("now") Instant now
    );
}
