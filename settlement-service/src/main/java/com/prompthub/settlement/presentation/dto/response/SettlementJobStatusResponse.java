package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "정산 배치잡 상태 조회 응답")
public record SettlementJobStatusResponse(

        @Schema(description = "Job Execution ID", example = "1024")
        Long jobExecutionId,

        @Schema(description = "Job 이름", example = "settlementJob")
        String jobName,

        @Schema(description = "실행 상태 (STARTING/STARTED/COMPLETED/FAILED/STOPPED 등)", example = "COMPLETED")
        String status,

        @Schema(description = "종료 코드 (COMPLETED/FAILED 등). 실행 중에는 UNKNOWN", example = "COMPLETED")
        String exitCode,

        @Schema(description = "시작 시각", example = "2026-06-03T02:00:00")
        LocalDateTime startTime,

        @Schema(description = "종료 시각. 실행 중이면 null", example = "2026-06-03T02:00:12")
        LocalDateTime endTime,

        @Schema(description = "실패 사유. 실패가 아니면 null")
        String failureMessage
) {

    public static SettlementJobStatusResponse from(SettlementJobStatusResult result) {
        return new SettlementJobStatusResponse(
                result.jobExecutionId(),
                result.jobName(),
                result.status(),
                result.exitCode(),
                result.startTime(),
                result.endTime(),
                result.failureMessage());
    }
}
