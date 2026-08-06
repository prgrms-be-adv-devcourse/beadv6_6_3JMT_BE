package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriod;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementAnalysisPeriodResolver {

    private static final String MONTH_PATTERN = "\\d{4}-\\d{2}";
    private static final String WEEK_PATTERN = "\\d{4}-\\d{2}-\\d{2}";

    private final Clock clock;

    public SettlementAnalysisPeriod resolve(
            SettlementAnalysisPeriodType type,
            String requestedPeriod) {
        if (type == null) {
            throw new IllegalArgumentException("정산 기간 유형이 필요합니다.");
        }
        return switch (type) {
            case MONTH -> resolveMonth(requestedPeriod);
            case WEEK -> resolveWeek(requestedPeriod);
        };
    }

    public ComparisonPeriods resolveComparison(
            SettlementAnalysisPeriodType type,
            String currentPeriod,
            String comparisonPeriod) {
        SettlementAnalysisPeriod current = resolve(type, currentPeriod);
        SettlementAnalysisPeriod comparison = resolve(type, comparisonPeriod);
        if (type != SettlementAnalysisPeriodType.MONTH) {
            return new ComparisonPeriods(current, comparison);
        }

        YearMonth currentMonth = parseMonth(currentPeriod);
        YearMonth comparisonMonth = parseMonth(comparisonPeriod);
        YearMonth actualCurrentMonth = YearMonth.from(today());
        if (!currentMonth.equals(actualCurrentMonth)
                && !comparisonMonth.equals(actualCurrentMonth)) {
            return new ComparisonPeriods(current, comparison);
        }

        LocalDate latestCompletedSunday = latestCompletedSunday();
        int comparisonDay = latestCompletedSunday.getDayOfMonth();
        return new ComparisonPeriods(
                truncateMonth(current, currentMonth, actualCurrentMonth, comparisonDay),
                truncateMonth(comparison, comparisonMonth, actualCurrentMonth, comparisonDay));
    }

    private SettlementAnalysisPeriod resolveMonth(String requestedPeriod) {
        YearMonth month = parseMonth(requestedPeriod);
        YearMonth currentMonth = YearMonth.from(today());
        LocalDate completedThrough = latestCompletedSunday();
        if (month.isAfter(currentMonth)) {
            throw new IllegalArgumentException("미래 정산 월은 조회할 수 없습니다.");
        }

        LocalDate includedStart = month.atDay(1);
        if (month.isBefore(currentMonth)) {
            LocalDate includedEnd = month.atEndOfMonth();
            return new SettlementAnalysisPeriod(
                    SettlementAnalysisPeriodType.MONTH,
                    requestedPeriod,
                    includedStart,
                    includedEnd,
                    includedEnd,
                    completedThrough,
                    false);
        }

        LocalDate dataThrough = completedThrough;
        LocalDate includedEnd = dataThrough.isBefore(includedStart) ? null : dataThrough;
        return new SettlementAnalysisPeriod(
                SettlementAnalysisPeriodType.MONTH,
                requestedPeriod,
                includedStart,
                includedEnd,
                dataThrough,
                completedThrough,
                true);
    }

    private SettlementAnalysisPeriod resolveWeek(String requestedPeriod) {
        LocalDate includedStart = parseWeek(requestedPeriod);
        if (includedStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("주간 정산 기간은 월요일이어야 합니다.");
        }
        LocalDate includedEnd = includedStart.plusDays(6);
        LocalDate completedThrough = latestCompletedSunday();
        if (includedEnd.isAfter(completedThrough)) {
            throw new IllegalArgumentException("완료되지 않은 주는 조회할 수 없습니다.");
        }
        return new SettlementAnalysisPeriod(
                SettlementAnalysisPeriodType.WEEK,
                requestedPeriod,
                includedStart,
                includedEnd,
                includedEnd,
                completedThrough,
                false);
    }

    private SettlementAnalysisPeriod truncateMonth(
            SettlementAnalysisPeriod period,
            YearMonth month,
            YearMonth actualCurrentMonth,
            int comparisonDay) {
        if (month.equals(actualCurrentMonth) && !period.hasIncludedDays()) {
            return period;
        }
        LocalDate includedEnd = month.atDay(Math.min(comparisonDay, month.lengthOfMonth()));
        return new SettlementAnalysisPeriod(
                period.type(),
                period.requestedPeriod(),
                period.includedStart(),
                includedEnd,
                includedEnd,
                period.completedThrough(),
                true);
    }

    private YearMonth parseMonth(String value) {
        if (value == null || !value.matches(MONTH_PATTERN)) {
            throw new IllegalArgumentException("정산 월 형식이 올바르지 않습니다.");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("정산 월 형식이 올바르지 않습니다.", exception);
        }
    }

    private LocalDate parseWeek(String value) {
        if (value == null || !value.matches(WEEK_PATTERN)) {
            throw new IllegalArgumentException("정산 주 형식이 올바르지 않습니다.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("정산 주 형식이 올바르지 않습니다.", exception);
        }
    }

    private LocalDate latestCompletedSunday() {
        LocalDate date = today().minusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    public record ComparisonPeriods(
            SettlementAnalysisPeriod current,
            SettlementAnalysisPeriod comparison) {
    }
}
