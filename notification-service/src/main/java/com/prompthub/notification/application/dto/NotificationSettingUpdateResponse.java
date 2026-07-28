package com.prompthub.notification.application.dto;

import com.prompthub.notification.domain.enums.NotificationCategory;
import java.time.Instant;

public record NotificationSettingUpdateResponse(
    NotificationCategory category,
    boolean enabled,
    boolean configurable,
    Instant updatedAt
) {
}
