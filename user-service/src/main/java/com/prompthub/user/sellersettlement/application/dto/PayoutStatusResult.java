package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public record PayoutStatusResult(
        YearMonth settlementMonth,
        List<PayoutStatusCountResult> statusCounts,
        List<WeeklyPayoutStatusResult> weeklySettlements) {

    public PayoutStatusResult {
        statusCounts = List.copyOf(statusCounts);
        weeklySettlements = List.copyOf(weeklySettlements);
    }

    public record PayoutStatusCountResult(SettlementDisplayStatus status, long count) {
    }

    public record WeeklyPayoutStatusResult(
            LocalDate periodStart,
            LocalDate periodEnd,
            SettlementDisplayStatus status,
            LocalDateTime paidAt) {
    }
}
