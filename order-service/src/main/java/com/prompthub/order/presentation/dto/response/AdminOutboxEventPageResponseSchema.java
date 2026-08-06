package com.prompthub.order.presentation.dto.response;

import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "PageResponseAdminOutboxEventResponse", description = "관리자 Outbox 이벤트 공통 페이지 응답")
public record AdminOutboxEventPageResponseSchema(
    @Schema(description = "성공 여부", example = "true")
    boolean success,

    @Schema(description = "실패한 Outbox 이벤트 목록")
    List<AdminOutboxEventResponse> data,

    @Schema(description = "응답 메시지", example = "success")
    String message,

    @Schema(description = "페이지 메타데이터")
    PageResponse.Meta meta
) {
}
