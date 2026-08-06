package com.prompthub.notification.application.service;

import com.prompthub.notification.application.dto.NotificationSettingItemResponse;
import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.dto.NotificationSettingsResponse;
import com.prompthub.notification.application.usecase.NotificationSettingUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.model.NotificationSetting;
import com.prompthub.notification.domain.repository.NotificationSettingRepository;
import com.prompthub.notification.global.exception.NotificationCustomException;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingService implements NotificationSettingUseCase {

    private static final Set<NotificationCategory> CONFIGURABLE_CATEGORIES =
        Set.of(NotificationCategory.PRODUCT, NotificationCategory.MARKETING);

    private final NotificationSettingRepository repository;

    @Override
    @Transactional(readOnly = true)
    public NotificationSettingsResponse getSettings(UUID recipientId) {
        Map<NotificationCategory, NotificationSetting> stored =
            repository.findAllByRecipientId(recipientId).stream()
                .collect(Collectors.toMap(
                    NotificationSetting::getCategory,
                    Function.identity()
                ));

        return new NotificationSettingsResponse(
            Arrays.stream(NotificationCategory.values())
                .map(category -> item(category, stored.get(category)))
                .toList()
        );
    }

    @Override
    @Transactional
    public NotificationSettingUpdateResponse updateSetting(
        UUID recipientId,
        NotificationCategory category,
        boolean enabled
    ) {
        validateConfigurable(category);
        Instant now = Instant.now();
        repository.upsert(
            UUID.randomUUID(),
            recipientId,
            category.name(),
            enabled,
            now
        );
        NotificationSetting stored = repository
            .findByRecipientIdAndCategory(recipientId, category)
            .orElseThrow(() -> new IllegalStateException(
                "notification setting upsert returned no row"
            ));
        return new NotificationSettingUpdateResponse(
            stored.getCategory(),
            stored.isEnabled(),
            true,
            stored.getUpdatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canReceive(UUID recipientId, NotificationCategory category) {
        if (!isConfigurable(category)) {
            return true;
        }
        return repository.findByRecipientIdAndCategory(recipientId, category)
            .map(NotificationSetting::isEnabled)
            .orElse(true);
    }

    private NotificationSettingItemResponse item(
        NotificationCategory category,
        NotificationSetting stored
    ) {
        boolean configurable = isConfigurable(category);
        boolean enabled = !configurable || stored == null || stored.isEnabled();
        return new NotificationSettingItemResponse(category, enabled, configurable);
    }

    private boolean isConfigurable(NotificationCategory category) {
        return CONFIGURABLE_CATEGORIES.contains(category);
    }

    private void validateConfigurable(NotificationCategory category) {
        if (!isConfigurable(category)) {
            throw new NotificationCustomException(
                NotificationErrorCode.NOTIFICATION_SETTING_NOT_CONFIGURABLE
            );
        }
    }
}
