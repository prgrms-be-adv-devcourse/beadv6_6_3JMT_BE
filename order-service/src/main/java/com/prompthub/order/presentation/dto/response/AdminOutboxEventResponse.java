package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "관리자용 실패 Outbox 이벤트 응답")
public record AdminOutboxEventResponse(
    @Schema(description = "Outbox 이벤트 ID", example = "00000000-0000-0000-0000-000000000902")
    UUID eventId,

    @Schema(description = "이벤트 집계 ID", example = "00000000-0000-0000-0000-000000000903")
    UUID aggregateId,

    @Schema(description = "이벤트 유형", example = "ORDER_PAID")
    String eventType,

    @Schema(description = "Outbox 이벤트 상태", example = "FAILED")
    OutboxEventStatus status,

    @Schema(description = "재시도 횟수", example = "3")
    int retryCount,

    @Schema(description = "이벤트 발생 시각", example = "2026-08-05T10:00:00")
    LocalDateTime occurredAt,

    @Schema(description = "마지막 발행 시도 시각", example = "2026-08-05T10:05:00")
    LocalDateTime lastAttemptAt,

    @Schema(description = "정제된 마지막 실패 원인", example = "Kafka timeout")
    String lastError
) {

    public static AdminOutboxEventResponse from(OutboxEventSummary summary) {
        return new AdminOutboxEventResponse(
            summary.eventId(),
            summary.aggregateId(),
            summary.eventType(),
            summary.status(),
            summary.retryCount(),
            summary.occurredAt(),
            summary.lastAttemptAt(),
            summary.lastError()
        );
    }
}
