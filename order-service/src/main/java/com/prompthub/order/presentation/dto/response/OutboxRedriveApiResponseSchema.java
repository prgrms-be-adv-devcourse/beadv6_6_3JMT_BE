package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ApiResultOutboxRedriveResponse", description = "Outbox 재처리 공통 성공 응답")
public record OutboxRedriveApiResponseSchema(
    @Schema(description = "성공 여부", example = "true")
    boolean success,

    @Schema(description = "Outbox 재처리 결과")
    OutboxRedriveResponse data,

    @Schema(description = "응답 메시지", example = "success")
    String message
) {
}
