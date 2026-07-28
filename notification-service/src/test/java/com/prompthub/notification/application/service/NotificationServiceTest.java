package com.prompthub.notification.application.service;

import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.application.dto.NotificationResponse;
import com.prompthub.notification.application.event.NotificationCreatedEvent;
import com.prompthub.notification.application.usecase.NotificationSettingUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.domain.repository.NotificationRepository;
import com.prompthub.notification.global.exception.NotificationCustomException;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    @Mock private NotificationRepository notificationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private NotificationSettingUseCase notificationSettingUseCase;
    @InjectMocks private NotificationService service;

    @Test
    void createNotification_disabledCategorySkipsSaveAndEvent() {
        CreateNotificationCommand command = command(NotificationCategory.MARKETING);
        given(notificationSettingUseCase.canReceive(RECIPIENT_ID, NotificationCategory.MARKETING)).willReturn(false);

        assertThat(service.createNotification(command)).isEmpty();

        then(notificationRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void createNotification_enabledCategorySavesAndPublishesEvent() {
        CreateNotificationCommand command = command(NotificationCategory.PRODUCT);
        given(notificationSettingUseCase.canReceive(RECIPIENT_ID, NotificationCategory.PRODUCT)).willReturn(true);
        given(notificationRepository.save(any(Notification.class))).willAnswer(invocation -> invocation.getArgument(0));

        Optional<NotificationResponse> result = service.createNotification(command);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().category()).isEqualTo("PRODUCT");
        then(eventPublisher).should().publishEvent(argThat((Object event) ->
            event instanceof NotificationCreatedEvent created && created.recipientId().equals(RECIPIENT_ID)
        ));
    }

    @Test
    void deleteNotification_ownedActiveNotificationDeletesEntityOnly() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = org.mockito.Mockito.mock(Notification.class);
        given(notificationRepository.findByIdAndRecipientIdAndExpiresAtAfter(
            eq(notificationId),
            eq(RECIPIENT_ID),
            any(Instant.class)
        )).willReturn(Optional.of(notification));

        service.deleteNotification(RECIPIENT_ID, notificationId);

        then(notificationRepository).should().delete(notification);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void deleteNotification_missingOwnedActiveNotificationThrowsN001() {
        UUID notificationId = UUID.randomUUID();
        given(notificationRepository.findByIdAndRecipientIdAndExpiresAtAfter(
            eq(notificationId),
            eq(RECIPIENT_ID),
            any(Instant.class)
        )).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteNotification(RECIPIENT_ID, notificationId))
            .isInstanceOf(NotificationCustomException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                NotificationErrorCode.NOTIFICATION_NOT_FOUND
            );

        then(notificationRepository).should(never()).delete(any());
    }

    @Test
    void deleteAllNotifications_delegatesOneActiveOnlyBulkDelete() {
        service.deleteAllNotifications(RECIPIENT_ID);

        then(notificationRepository).should().deleteAllActiveByRecipientId(
            eq(RECIPIENT_ID),
            any(Instant.class)
        );
        then(eventPublisher).shouldHaveNoInteractions();
    }

    private CreateNotificationCommand command(NotificationCategory category) {
        return new CreateNotificationCommand(
            RECIPIENT_ID,
            NotificationType.ORDER_CREATED,
            category,
            "알림 제목",
            "알림 내용",
            "/mypage",
            "ORDER",
            UUID.randomUUID(),
            Instant.parse("2026-07-28T01:00:00Z"),
            category.name() + ":" + UUID.randomUUID()
        );
    }
}
