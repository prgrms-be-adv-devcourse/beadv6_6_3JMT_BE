package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Outbox 이벤트 재처리 요청 결과")
public record OutboxRedriveResponse(
    @Schema(description = "Outbox 이벤트 ID", example = "00000000-0000-0000-0000-000000000902")
    UUID eventId,

    @Schema(description = "재처리 후 Outbox 이벤트 상태", example = "PENDING")
    OutboxEventStatus status,

    @Schema(description = "다음 발행 예정 시각", example = "2026-08-05T10:05:00")
    LocalDateTime nextAttemptAt
) {

    public static OutboxRedriveResponse from(OutboxRedriveResult result) {
        return new OutboxRedriveResponse(
            result.eventId(),
            result.status(),
            result.nextAttemptAt()
        );
    }
}
