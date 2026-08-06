package com.prompthub.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.notification.application.dto.NotificationSettingItemResponse;
import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.dto.NotificationSettingsResponse;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.model.NotificationSetting;
import com.prompthub.notification.domain.repository.NotificationSettingRepository;
import com.prompthub.notification.global.exception.NotificationCustomException;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTest {

    @Mock
    private NotificationSettingRepository repository;

    @InjectMocks
    private NotificationSettingService service;

    @Test
    void getSettings_mergesStoredRowsWithDefaultsInEnumOrder() {
        UUID recipientId = UUID.randomUUID();
        NotificationSetting marketing = mock(NotificationSetting.class);
        given(marketing.getCategory()).willReturn(NotificationCategory.MARKETING);
        given(marketing.isEnabled()).willReturn(false);
        given(repository.findAllByRecipientId(recipientId)).willReturn(List.of(marketing));

        NotificationSettingsResponse result = service.getSettings(recipientId);

        assertThat(result.settings()).containsExactly(
            new NotificationSettingItemResponse(NotificationCategory.ORDER, true, false),
            new NotificationSettingItemResponse(NotificationCategory.PAYMENT, true, false),
            new NotificationSettingItemResponse(NotificationCategory.REFUND, true, false),
            new NotificationSettingItemResponse(NotificationCategory.PRODUCT, true, true),
            new NotificationSettingItemResponse(NotificationCategory.SYSTEM, true, false),
            new NotificationSettingItemResponse(NotificationCategory.MARKETING, false, true)
        );
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationCategory.class,
        names = {"ORDER", "PAYMENT", "REFUND", "SYSTEM"}
    )
    void canReceive_mandatoryCategoryReturnsTrueWithoutRepository(
        NotificationCategory category
    ) {
        assertThat(service.canReceive(UUID.randomUUID(), category)).isTrue();
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void canReceive_absentOptionalSettingDefaultsToTrue() {
        UUID recipientId = UUID.randomUUID();
        given(repository.findByRecipientIdAndCategory(
            recipientId, NotificationCategory.PRODUCT
        )).willReturn(Optional.empty());

        assertThat(service.canReceive(recipientId, NotificationCategory.PRODUCT)).isTrue();
    }

    @Test
    void canReceive_disabledOptionalSettingReturnsFalse() {
        UUID recipientId = UUID.randomUUID();
        NotificationSetting setting = mock(NotificationSetting.class);
        given(setting.isEnabled()).willReturn(false);
        given(repository.findByRecipientIdAndCategory(
            recipientId, NotificationCategory.MARKETING
        )).willReturn(Optional.of(setting));

        assertThat(service.canReceive(recipientId, NotificationCategory.MARKETING)).isFalse();
    }

    @Test
    void updateSetting_upsertsOptionalCategoryAndReturnsStoredState() {
        UUID recipientId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-07-28T04:00:00Z");
        NotificationSetting stored = mock(NotificationSetting.class);
        given(stored.getCategory()).willReturn(NotificationCategory.MARKETING);
        given(stored.isEnabled()).willReturn(false);
        given(stored.getUpdatedAt()).willReturn(updatedAt);
        given(repository.findByRecipientIdAndCategory(
            recipientId, NotificationCategory.MARKETING
        )).willReturn(Optional.of(stored));

        NotificationSettingUpdateResponse result = service.updateSetting(
            recipientId,
            NotificationCategory.MARKETING,
            false
        );

        assertThat(result).isEqualTo(new NotificationSettingUpdateResponse(
            NotificationCategory.MARKETING,
            false,
            true,
            updatedAt
        ));
        then(repository).should().upsert(
            any(UUID.class),
            eq(recipientId),
            eq("MARKETING"),
            eq(false),
            any(Instant.class)
        );
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationCategory.class,
        names = {"ORDER", "PAYMENT", "REFUND", "SYSTEM"}
    )
    void updateSetting_rejectsMandatoryCategory(NotificationCategory category) {
        assertThatThrownBy(() -> service.updateSetting(
            UUID.randomUUID(), category, false
        ))
            .isInstanceOf(NotificationCustomException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                NotificationErrorCode.NOTIFICATION_SETTING_NOT_CONFIGURABLE
            );

        then(repository).shouldHaveNoInteractions();
    }
}
