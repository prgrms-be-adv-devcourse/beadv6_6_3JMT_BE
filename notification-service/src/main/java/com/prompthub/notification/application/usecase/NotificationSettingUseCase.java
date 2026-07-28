package com.prompthub.notification.application.usecase;

import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.dto.NotificationSettingsResponse;
import com.prompthub.notification.domain.enums.NotificationCategory;
import java.util.UUID;

public interface NotificationSettingUseCase {

    NotificationSettingsResponse getSettings(UUID recipientId);

    NotificationSettingUpdateResponse updateSetting(
        UUID recipientId,
        NotificationCategory category,
        boolean enabled
    );

    boolean canReceive(UUID recipientId, NotificationCategory category);
}
