package com.prompthub.settlement.domain.model;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record SettlementPeriod(LocalDate periodStart, LocalDate periodEnd) {

    public SettlementPeriod {
        Objects.requireNonNull(periodStart, "정산 시작일은 필수입니다.");
        Objects.requireNonNull(periodEnd, "정산 종료일은 필수입니다.");
        if (periodStart.getDayOfWeek() != MONDAY) {
            throw new IllegalArgumentException("정산 시작일은 월요일이어야 합니다.");
        }
        if (!periodEnd.equals(periodStart.plusDays(6))) {
            throw new IllegalArgumentException("정산 종료일은 시작일 다음 일요일이어야 합니다.");
        }
    }

    public static SettlementPeriod of(LocalDate periodStart, LocalDate periodEnd) {
        return new SettlementPeriod(periodStart, periodEnd);
    }

    public static SettlementPeriod previousWeek(LocalDate executionDate) {
        Objects.requireNonNull(executionDate, "실행일은 필수입니다.");
        LocalDate currentWeekStart = executionDate.with(previousOrSame(MONDAY));
        return of(currentWeekStart.minusWeeks(1), currentWeekStart.minusDays(1));
    }

    public LocalDateTime startInclusive() {
        return periodStart.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return periodEnd.plusDays(1).atStartOfDay();
    }
}
