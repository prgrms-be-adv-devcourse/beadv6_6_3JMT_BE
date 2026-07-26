package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriod;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("정산 분석 기간 해석")
class SettlementAnalysisPeriodResolverTest {

    private SettlementAnalysisPeriodResolver resolver;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-22T03:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        resolver = new SettlementAnalysisPeriodResolver(clock);
    }

    @Test
    @DisplayName("현재 월은 최신 완료 일요일까지만 부분 기간으로 해석한다")
    void resolvesCurrentMonthThroughLatestCompletedSunday() {
        SettlementAnalysisPeriod period = resolver.resolve(
                SettlementAnalysisPeriodType.MONTH, "2026-07");

        assertThat(period.includedStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(period.includedEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(period.dataThrough()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(period.partial()).isTrue();
        assertThat(period.hasIncludedDays()).isTrue();
    }

    @Test
    @DisplayName("월 경계를 지나는 완료 주는 월요일부터 일요일 전체로 해석한다")
    void resolvesCompletedBoundaryWeek() {
        SettlementAnalysisPeriod period = resolver.resolve(
                SettlementAnalysisPeriodType.WEEK, "2026-06-29");

        assertThat(period.includedStart()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(period.includedEnd()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(period.partial()).isFalse();
    }

    @Test
    @DisplayName("현재 부분 월 비교는 두 월 모두 같은 일자까지만 포함한다")
    void truncatesComparisonToSameDaySpan() {
        SettlementAnalysisPeriodResolver.ComparisonPeriods periods =
                resolver.resolveComparison(
                        SettlementAnalysisPeriodType.MONTH, "2026-07", "2026-06");

        assertThat(periods.current().includedEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(periods.comparison().includedEnd()).isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(periods.current().partial()).isTrue();
        assertThat(periods.comparison().partial()).isTrue();
        assertThat(periods.comparison().dataThrough()).isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(periods.comparison().completedThrough())
                .isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    @DisplayName("현재 월에 완료된 일자가 아직 없으면 빈 포함 범위를 반환한다")
    void resolvesEmptyCurrentMonthBeforeFirstCompletedSunday() {
        SettlementAnalysisPeriodResolver firstDayResolver = new SettlementAnalysisPeriodResolver(
                Clock.fixed(
                        Instant.parse("2026-07-01T03:00:00Z"),
                        ZoneId.of("Asia/Seoul")));

        SettlementAnalysisPeriod period = firstDayResolver.resolve(
                SettlementAnalysisPeriodType.MONTH, "2026-07");

        assertThat(period.includedEnd()).isNull();
        assertThat(period.dataThrough()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(period.hasIncludedDays()).isFalse();
    }

    @Test
    @DisplayName("월요일이 아닌 주와 미래 월은 거부한다")
    void rejectsRepresentativeInvalidPeriods() {
        assertThatThrownBy(() -> resolver.resolve(
                SettlementAnalysisPeriodType.WEEK, "2026-07-14"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(
                SettlementAnalysisPeriodType.MONTH, "2026-08"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
