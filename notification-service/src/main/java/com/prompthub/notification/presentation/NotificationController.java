package com.prompthub.notification.presentation;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.notification.application.dto.NotificationReadResponse;
import com.prompthub.notification.application.dto.NotificationResponse;
import com.prompthub.notification.application.dto.ReadAllNotificationsResponse;
import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.dto.NotificationSettingsResponse;
import com.prompthub.notification.application.dto.UnreadNotificationCountResponse;
import com.prompthub.notification.application.usecase.NotificationSettingUseCase;
import com.prompthub.notification.application.usecase.NotificationUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.infra.realtime.SseConnectionRegistry;
import com.prompthub.notification.presentation.dto.request.UpdateNotificationSettingRequest;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(
    name = "Notification",
    description = "알림 조회, 읽음, 삭제 및 카테고리별 수신 설정 API"
)
public class NotificationController {

    private static final String USER_ID = "X-User-Id";

    private final NotificationUseCase notificationUseCase;
    private final NotificationSettingUseCase notificationSettingUseCase;
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

    @Operation(
        summary = "알림 설정 조회",
        description = "전체 카테고리의 수신 여부와 변경 가능 여부를 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "조회 성공"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "사용자 헤더 오류 V001",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @GetMapping("/settings")
    public ApiResult<NotificationSettingsResponse> getSettings(
        @Parameter(hidden = true) @RequestHeader(USER_ID) UUID recipientId
    ) {
        return ApiResult.success(notificationSettingUseCase.getSettings(recipientId));
    }

    @Operation(
        summary = "알림 설정 변경",
        description = "PRODUCT 또는 MARKETING 카테고리의 수신 여부를 변경합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "변경 성공"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "입력 오류 V001 또는 변경 불가 카테고리 N003",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PutMapping("/settings/{category}")
    public ApiResult<NotificationSettingUpdateResponse> updateSetting(
        @Parameter(hidden = true) @RequestHeader(USER_ID) UUID recipientId,
        @Parameter(description = "알림 카테고리") @PathVariable NotificationCategory category,
        @Valid @RequestBody UpdateNotificationSettingRequest request
    ) {
        return ApiResult.success(notificationSettingUseCase.updateSetting(
            recipientId,
            category,
            request.enabled()
        ));
    }

    @Operation(
        summary = "알림 단건 삭제",
        description = "본인의 만료되지 않은 알림 한 건을 물리 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(
            responseCode = "400",
            description = "요청 값 오류 V001",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 없음 N001",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(
        @Parameter(hidden = true) @RequestHeader(USER_ID) UUID recipientId,
        @Parameter(description = "알림 ID") @PathVariable UUID notificationId
    ) {
        notificationUseCase.deleteNotification(recipientId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "알림 전체 삭제",
        description = "본인의 만료되지 않은 알림 전체를 물리 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(
            responseCode = "400",
            description = "사용자 헤더 오류 V001",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @DeleteMapping
    public ResponseEntity<Void> deleteAllNotifications(
        @Parameter(hidden = true) @RequestHeader(USER_ID) UUID recipientId
    ) {
        notificationUseCase.deleteAllNotifications(recipientId);
        return ResponseEntity.noContent().build();
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
