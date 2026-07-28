package com.prompthub.notification.application.dto;

import java.util.List;

public record NotificationSettingsResponse(
    List<NotificationSettingItemResponse> settings
) {
    public NotificationSettingsResponse {
        settings = List.copyOf(settings);
    }
}
