package com.prompthub.notification.presentation;

import com.prompthub.notification.application.dto.NotificationReadResponse;
import com.prompthub.notification.application.dto.NotificationResponse;
import com.prompthub.notification.application.dto.ReadAllNotificationsResponse;
import com.prompthub.notification.application.dto.UnreadNotificationCountResponse;
import com.prompthub.notification.application.usecase.NotificationUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.infra.realtime.SseConnectionRegistry;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final String USER_ID = "X-User-Id";

    private final NotificationUseCase notificationUseCase;
    private final SseConnectionRegistry connectionRegistry;

    @GetMapping
    public PageResponse<NotificationResponse> getNotifications(
        @RequestHeader(USER_ID) UUID recipientId,
        @RequestParam(required = false) NotificationCategory category,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int resolvedSize = Math.min(Math.max(size, 1), 100);
        int resolvedPage = Math.max(page, 1);
        Page<NotificationResponse> result = notificationUseCase.getNotifications(recipientId, category, resolvedPage, resolvedSize);
        return PageResponse.success(result.getContent(), resolvedPage, resolvedSize, result.getTotalElements(), result.hasNext());
    }

    @GetMapping("/unread-count")
    public ApiResult<UnreadNotificationCountResponse> getUnreadCount(@RequestHeader(USER_ID) UUID recipientId) {
        return ApiResult.success(notificationUseCase.getUnreadCount(recipientId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @RequestHeader(USER_ID) UUID recipientId,
        @RequestHeader(value = "Last-Event-ID", required = false) UUID lastEventId
    ) {
        return connectionRegistry.register(recipientId, () -> notificationUseCase.getReplay(recipientId, lastEventId));
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResult<NotificationReadResponse> readNotification(
        @RequestHeader(USER_ID) UUID recipientId,
        @PathVariable UUID notificationId
    ) {
        return ApiResult.success(notificationUseCase.readNotification(recipientId, notificationId));
    }

    @PatchMapping("/read-all")
    public ApiResult<ReadAllNotificationsResponse> readAllNotifications(@RequestHeader(USER_ID) UUID recipientId) {
        return ApiResult.success(notificationUseCase.readAllNotifications(recipientId));
    }
}
