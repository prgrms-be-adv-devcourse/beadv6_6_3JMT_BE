package com.prompthub.user.sellersettlement.application.dto;

import java.time.LocalDate;

public record SettlementAnalysisPeriod(
        SettlementAnalysisPeriodType type,
        String requestedPeriod,
        LocalDate includedStart,
        LocalDate includedEnd,
        LocalDate dataThrough,
        LocalDate completedThrough,
        boolean partial) {

    public boolean hasIncludedDays() {
        return includedEnd != null && !includedEnd.isBefore(includedStart);
    }
}
