package com.prompthub.settlement.application.dto;

import java.time.LocalDateTime;

public record SettlementJobStatusResult(
        Long jobExecutionId,
        String jobName,
        String status,
        String exitCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String failureMessage
) {
}
