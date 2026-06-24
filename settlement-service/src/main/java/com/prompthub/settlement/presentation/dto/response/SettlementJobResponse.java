package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "정산 배치잡 실행 응답")
public record SettlementJobResponse(

        @Schema(description = "Job Execution ID", example = "1024")
        Long jobExecutionId,

        @Schema(description = "Job 이름", example = "settlementJob")
        String jobName,

        @Schema(description = "실행 상태(비동기 접수 시점에는 STARTING/STARTED)", example = "STARTING")
        String status,

        @Schema(description = "시작 시각", example = "2026-06-03T02:00:00")
        LocalDateTime startTime
) {

    public static SettlementJobResponse from(SettlementJobResult result) {
        return new SettlementJobResponse(
                result.jobExecutionId(),
                result.jobName(),
                result.status(),
                result.startTime()
        );
    }
}
