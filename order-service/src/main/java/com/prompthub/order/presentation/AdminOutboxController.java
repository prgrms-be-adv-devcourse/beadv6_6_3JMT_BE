package com.prompthub.order.presentation;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.application.usecase.OutboxAdminUseCase;
import com.prompthub.order.presentation.dto.request.OutboxPageRequest;
import com.prompthub.order.presentation.dto.request.RedriveOutboxRequest;
import com.prompthub.order.presentation.dto.response.AdminOutboxEventResponse;
import com.prompthub.order.presentation.dto.response.AdminOutboxEventPageResponseSchema;
import com.prompthub.order.presentation.dto.response.OutboxRedriveResponse;
import com.prompthub.order.presentation.dto.response.OutboxRedriveApiResponseSchema;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.global.web.AuthHeaders.USER_ID;

@RestController
@RequestMapping("/api/v1/admin/outbox-events")
@RequiredArgsConstructor
@Tag(name = "Admin Outbox", description = "관리자 Outbox 실패 이벤트 조회 및 재처리 API")
@SecurityRequirement(name = "Bearer")
public class AdminOutboxController {

    private final OutboxAdminUseCase outboxAdminUseCase;

    @GetMapping
    @Operation(summary = "실패한 Outbox 이벤트 조회", description = "실패 상태의 Outbox 이벤트를 1-based 페이지로 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "실패한 Outbox 이벤트 조회 성공",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = AdminOutboxEventPageResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "V001 페이지 번호 또는 크기 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PageResponse<AdminOutboxEventResponse> getFailedEvents(
        @Valid @ModelAttribute OutboxPageRequest request
    ) {
        OutboxPageRequest resolvedRequest = request.resolve();
        Page<OutboxEventSummary> events = outboxAdminUseCase.getFailedEvents(
            resolvedRequest.page(),
            resolvedRequest.size()
        );
        List<AdminOutboxEventResponse> responses = events.getContent().stream()
            .map(AdminOutboxEventResponse::from)
            .toList();

        return PageResponse.success(
            responses,
            resolvedRequest.page(),
            resolvedRequest.size(),
            events.getTotalElements(),
            events.hasNext()
        );
    }

    @PostMapping("/{eventId}/redrive")
    @Operation(summary = "Outbox 이벤트 재처리", description = "실패 상태의 Outbox 이벤트를 재처리 대기 상태로 전환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Outbox 이벤트 재처리 요청 성공",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = OutboxRedriveApiResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "V001 X-User-Id, 경로 변수 또는 재처리 사유 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "A003 X-User-Id 인증 정보 누락",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "O020 Outbox 이벤트 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "O021 재처리할 수 없는 Outbox 이벤트 상태",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<OutboxRedriveResponse> redrive(
        @Parameter(hidden = true)
        @RequestHeader(USER_ID) UUID requestedBy,
        @Parameter(description = "Outbox 이벤트 ID", example = "00000000-0000-0000-0000-000000000902")
        @PathVariable UUID eventId,
        @Valid @RequestBody RedriveOutboxRequest request
    ) {
        return ApiResult.success(OutboxRedriveResponse.from(
            outboxAdminUseCase.redrive(eventId, requestedBy, request.reason())
        ));
    }
}
