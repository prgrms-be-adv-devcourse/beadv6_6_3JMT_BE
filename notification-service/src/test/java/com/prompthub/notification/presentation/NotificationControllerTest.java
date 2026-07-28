package com.prompthub.notification.presentation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.notification.application.dto.NotificationSettingItemResponse;
import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.dto.NotificationSettingsResponse;
import com.prompthub.notification.application.usecase.NotificationSettingUseCase;
import com.prompthub.notification.application.usecase.NotificationUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.global.exception.NotificationCustomException;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import com.prompthub.notification.global.exception.NotificationExceptionHandler;
import com.prompthub.notification.infra.realtime.SseConnectionRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    @Mock
    private NotificationUseCase notificationUseCase;

    @Mock
    private NotificationSettingUseCase notificationSettingUseCase;

    @Mock
    private SseConnectionRegistry connectionRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new NotificationController(
                    notificationUseCase,
                    notificationSettingUseCase,
                    connectionRegistry
                )
            )
            .setControllerAdvice(new NotificationExceptionHandler())
            .setValidator(validator)
            .build();
    }

    @Test
    void stream_delegatesToConnectionRegistry() {
        SseEmitter emitter = new SseEmitter();
        given(connectionRegistry.register(org.mockito.ArgumentMatchers.eq(RECIPIENT_ID),
            org.mockito.ArgumentMatchers.any())).willReturn(emitter);

        org.assertj.core.api.Assertions.assertThat(
            new NotificationController(
                notificationUseCase,
                notificationSettingUseCase,
                connectionRegistry
            ).stream(RECIPIENT_ID, null)
        ).isSameAs(emitter);
    }

    @Test
    void getSettings_returnsOrderedSettings() throws Exception {
        given(notificationSettingUseCase.getSettings(RECIPIENT_ID))
            .willReturn(new NotificationSettingsResponse(List.of(
                new NotificationSettingItemResponse(NotificationCategory.ORDER, true, false),
                new NotificationSettingItemResponse(NotificationCategory.PRODUCT, false, true)
            )));

        mockMvc.perform(get("/api/v1/notifications/settings")
                .header("X-User-Id", RECIPIENT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.settings[0].category").value("ORDER"))
            .andExpect(jsonPath("$.data.settings[0].configurable").value(false))
            .andExpect(jsonPath("$.data.settings[1].category").value("PRODUCT"))
            .andExpect(jsonPath("$.data.settings[1].enabled").value(false));
    }

    @Test
    void updateSetting_returnsPersistedState() throws Exception {
        Instant updatedAt = Instant.parse("2026-07-28T04:00:00Z");
        given(notificationSettingUseCase.updateSetting(
            RECIPIENT_ID, NotificationCategory.MARKETING, false
        )).willReturn(new NotificationSettingUpdateResponse(
            NotificationCategory.MARKETING, false, true, updatedAt
        ));

        mockMvc.perform(put("/api/v1/notifications/settings/{category}", "MARKETING")
                .header("X-User-Id", RECIPIENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.category").value("MARKETING"))
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.configurable").value(true))
            .andExpect(jsonPath("$.data.updatedAt").value("2026-07-28T04:00:00Z"));
    }

    @Test
    void deleteNotification_returnsNoContent() throws Exception {
        UUID notificationId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/notifications/{notificationId}", notificationId)
                .header("X-User-Id", RECIPIENT_ID))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        then(notificationUseCase).should().deleteNotification(RECIPIENT_ID, notificationId);
    }

    @Test
    void deleteAllNotifications_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications")
                .header("X-User-Id", RECIPIENT_ID))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        then(notificationUseCase).should().deleteAllNotifications(RECIPIENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":null}", "{\"enabled\":\"not-boolean\"}"})
    void updateSetting_invalidBodyReturnsV001(String body) throws Exception {
        mockMvc.perform(put("/api/v1/notifications/settings/{category}", "MARKETING")
                .header("X-User-Id", RECIPIENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void updateSetting_nullJsonBodyReturnsV001WithoutCallingUseCases() throws Exception {
        mockMvc.perform(put("/api/v1/notifications/settings/{category}", "MARKETING")
                .header("X-User-Id", RECIPIENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));

        then(notificationUseCase).shouldHaveNoInteractions();
        then(notificationSettingUseCase).shouldHaveNoInteractions();
    }

    @Test
    void updateSetting_mandatoryCategoryReturnsN003() throws Exception {
        willThrow(new NotificationCustomException(
            NotificationErrorCode.NOTIFICATION_SETTING_NOT_CONFIGURABLE
        )).given(notificationSettingUseCase)
            .updateSetting(RECIPIENT_ID, NotificationCategory.ORDER, false);

        mockMvc.perform(put("/api/v1/notifications/settings/{category}", "ORDER")
                .header("X-User-Id", RECIPIENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("N003"));
    }

    @Test
    void updateSetting_unknownCategoryReturnsV001() throws Exception {
        mockMvc.perform(put("/api/v1/notifications/settings/{category}", "UNKNOWN")
                .header("X-User-Id", RECIPIENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void deleteNotification_notFoundReturnsN001() throws Exception {
        UUID notificationId = UUID.randomUUID();
        willThrow(new NotificationCustomException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
            .given(notificationUseCase).deleteNotification(RECIPIENT_ID, notificationId);

        mockMvc.perform(delete("/api/v1/notifications/{notificationId}", notificationId)
                .header("X-User-Id", RECIPIENT_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("N001"));
    }

    @Test
    void getSettings_missingUserHeaderReturnsV001() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/settings"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));

        then(notificationUseCase).shouldHaveNoInteractions();
        then(notificationSettingUseCase).shouldHaveNoInteractions();
    }

    @Test
    void deleteAllNotifications_invalidUserHeaderReturnsV001() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications")
                .header("X-User-Id", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));

        then(notificationUseCase).shouldHaveNoInteractions();
        then(notificationSettingUseCase).shouldHaveNoInteractions();
    }
}
