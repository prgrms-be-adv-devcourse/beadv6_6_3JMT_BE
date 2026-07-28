package com.prompthub.notification.application.dto;

import com.prompthub.notification.domain.enums.NotificationCategory;

public record NotificationSettingItemResponse(
    NotificationCategory category,
    boolean enabled,
    boolean configurable
) {
}
