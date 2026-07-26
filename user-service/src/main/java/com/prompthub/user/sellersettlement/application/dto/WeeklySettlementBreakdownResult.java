package com.prompthub.user.sellersettlement.application.dto;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record WeeklySettlementBreakdownResult(
        YearMonth requestedMonth,
        boolean partial,
        LocalDate dataThrough,
        List<WeeklySettlementBucketResult> weeks) {

    public WeeklySettlementBreakdownResult {
        weeks = List.copyOf(weeks);
    }

    public record WeeklySettlementBucketResult(
            LocalDate weekStart,
            LocalDate weekEnd,
            boolean boundaryWeek,
            SettlementAnalysisResult aggregate) {
    }
}
