package com.prompthub.settlement.application.dto;

import java.time.LocalDateTime;

public record SettlementJobResult(
        Long jobExecutionId,
        String jobName,
        String status,
        LocalDateTime startTime
) {
}
