package com.prompthub.notification.presentation;

import com.prompthub.notification.application.service.NotificationPage;
import com.prompthub.notification.application.service.NotificationItem;
import com.prompthub.notification.application.service.NotificationQueryService;
import com.prompthub.notification.application.service.NotificationCommandService;
import com.prompthub.notification.infra.sse.SseConnectionRegistry;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/notifications")
@Tag(name = "알림", description = "주문 결제·환불 알림 조회 및 읽음 처리")
@SecurityRequirement(name = "Bearer")
public class NotificationController {
    private final NotificationQueryService queryService;
    private final NotificationCommandService commandService;
    private final SseConnectionRegistry sseConnectionRegistry;

    @GetMapping
    @Operation(summary = "알림 목록 조회", description = "Gateway가 주입한 사용자 ID 기준으로 알림을 페이지 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public PageResponse<NotificationItem> list(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        NotificationPage result = queryService.findPage(userId, page, size);
        return PageResponse.success(result.items(), page, size, result.total(), result.hasNext());
    }

    @Operation(summary = "미읽음 알림 수 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/unread-count")
    public ApiResult<UnreadCountResponse> unreadCount(@Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId) {
        return ApiResult.success(new UnreadCountResponse(queryService.countUnread(userId)));
    }

    @Operation(summary = "실시간 알림 스트림 연결", description = "Last-Event-ID 이후에 누락된 알림을 재전송한 뒤 실시간 알림을 전달합니다.")
    @ApiResponse(responseCode = "200", description = "SSE 연결 성공")
    @ApiResponse(responseCode = "429", description = "사용자별 SSE 연결 한도 초과 (N003)")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        long cursor = lastEventId == null ? 0L : Long.parseLong(lastEventId);
        SseEmitter emitter = sseConnectionRegistry.connect(userId);
        queryService.findCreatedAfter(userId, cursor)
            .forEach(notification -> sseConnectionRegistry.send(userId, emitter, notification));
        return emitter;
    }

    @Operation(summary = "알림 하나 읽음 처리")
    @ApiResponse(responseCode = "200", description = "읽음 처리 성공")
    @ApiResponse(responseCode = "403", description = "다른 사용자의 알림 (N002)")
    @ApiResponse(responseCode = "404", description = "알림 없음 (N001)")
    @PatchMapping("/{notificationId}/read")
    public ApiResult<Void> markRead(@Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID notificationId) {
        commandService.markRead(userId, notificationId);
        return ApiResult.success();
    }

    @Operation(summary = "전체 알림 읽음 처리")
    @ApiResponse(responseCode = "200", description = "읽음 처리 성공")
    @PatchMapping("/read")
    public ApiResult<Void> markAllRead(@Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId) {
        commandService.markAllRead(userId);
        return ApiResult.success();
    }
}
