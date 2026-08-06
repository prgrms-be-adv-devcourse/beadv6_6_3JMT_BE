package com.prompthub.order.presentation.dto.request;

import com.prompthub.order.presentation.dto.request.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Outbox 이벤트 재처리 요청")
public record RedriveOutboxRequest(
    @NotBlank
    @CodePointLength(max = 500)
    @Schema(description = "Outbox 재처리 사유", example = "Kafka 복구 확인", maxLength = 500)
    String reason
) {
}
