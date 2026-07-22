package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriod;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult;
import com.prompthub.user.sellersettlement.application.dto.WeeklySettlementBreakdownResult;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisQueryRange;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusCount;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusRow;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusSnapshot;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.WeeklyAnalysisAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 정산 분석 애플리케이션 서비스")
class SellerSettlementAnalysisApplicationServiceTest {

    @Mock
    private SellerSettlementAnalysisQueryRepository repository;

    @Mock
    private SettlementAnalysisPeriodResolver periodResolver;

    private SellerSettlementAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SellerSettlementAnalysisApplicationService(
                repository, periodResolver, new SettlementChangeCalculator());
    }

    @Test
    @DisplayName("네 조회는 metadata actor를 seller 조건으로 전달하고 계산 결과를 조립한다")
    void assemblesFourReadOnlyQueriesForActor() {
        UUID actorId = UUID.randomUUID();
        SettlementAnalysisPeriod week = period(
                SettlementAnalysisPeriodType.WEEK,
                "2026-07-13",
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                false);
        SettlementAnalysisPeriod currentMonth = period(
                SettlementAnalysisPeriodType.MONTH,
                "2026-07",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 19),
                true);
        SettlementAnalysisPeriod comparisonMonth = period(
                SettlementAnalysisPeriodType.MONTH,
                "2026-06",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 19),
                true);
        given(periodResolver.resolve(SettlementAnalysisPeriodType.WEEK, "2026-07-13"))
                .willReturn(week);
        given(periodResolver.resolveComparison(
                SettlementAnalysisPeriodType.MONTH, "2026-07", "2026-06"))
                .willReturn(new SettlementAnalysisPeriodResolver.ComparisonPeriods(
                        currentMonth, comparisonMonth));
        given(periodResolver.resolve(SettlementAnalysisPeriodType.MONTH, "2026-07"))
                .willReturn(currentMonth);
        given(periodResolver.resolve(SettlementAnalysisPeriodType.MONTH, "2026-06"))
                .willReturn(comparisonMonth);

        AnalysisAggregate weekAggregate = aggregate(3, "255");
        AnalysisAggregate currentAggregate = aggregate(2, "170");
        AnalysisAggregate comparisonAggregate = aggregate(1, "85");
        given(repository.aggregate(actorId, range(week))).willReturn(weekAggregate);
        given(repository.aggregate(actorId, range(currentMonth))).willReturn(currentAggregate);
        given(repository.aggregate(actorId, range(comparisonMonth)))
                .willReturn(comparisonAggregate);
        WeeklyAnalysisAggregate weekly = new WeeklyAnalysisAggregate(
                LocalDate.of(2026, 6, 29),
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 5),
                true,
                aggregate(1, "85"));
        given(repository.findWeeklyBreakdown(
                actorId,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 19)))
                .willReturn(List.of(weekly));
        PayoutStatusSnapshot payoutSnapshot = new PayoutStatusSnapshot(
                List.of(new PayoutStatusCount(SettlementDisplayStatus.PAID, 1)),
                List.of(new PayoutStatusRow(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 7),
                        SettlementDisplayStatus.PAID,
                        LocalDateTime.of(2026, 6, 10, 12, 0))));
        given(repository.findPayoutStatuses(actorId, YearMonth.of(2026, 6)))
                .willReturn(payoutSnapshot);

        SettlementAnalysisResult summary = service.getSummary(
                actorId, SettlementAnalysisPeriodType.WEEK, "2026-07-13");
        SettlementComparisonResult comparison = service.compare(
                actorId, SettlementAnalysisPeriodType.MONTH, "2026-07", "2026-06");
        WeeklySettlementBreakdownResult breakdown = service.getWeeklyBreakdown(
                actorId, YearMonth.of(2026, 7));
        PayoutStatusResult payout = service.getPayoutStatus(
                actorId, YearMonth.of(2026, 6));

        assertThat(summary.saleCount()).isEqualTo(3);
        assertThat(comparison.changes().saleCount().difference()).isEqualTo(1);
        assertThat(comparison.changes().payoutAmount().difference())
                .isEqualByComparingTo("85");
        assertThat(breakdown.partial()).isTrue();
        assertThat(breakdown.dataThrough()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(breakdown.weeks().getFirst().boundaryWeek()).isTrue();
        assertThat(payout.statusCounts()).singleElement()
                .satisfies(count -> assertThat(count.status()).isEqualTo(SettlementDisplayStatus.PAID));

        then(repository).should().aggregate(actorId, range(week));
        then(repository).should().aggregate(actorId, range(currentMonth));
        then(repository).should().aggregate(actorId, range(comparisonMonth));
        then(repository).should().findWeeklyBreakdown(
                actorId,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 19));
        then(repository).should().findPayoutStatuses(actorId, YearMonth.of(2026, 6));
    }

    @Test
    @DisplayName("저장소 장애를 부분 데이터로 삼키지 않고 전파한다")
    void propagatesRepositoryFailure() {
        UUID actorId = UUID.randomUUID();
        SettlementAnalysisPeriod period = period(
                SettlementAnalysisPeriodType.MONTH,
                "2026-06",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                false);
        IllegalStateException failure = new IllegalStateException("db unavailable");
        given(periodResolver.resolve(SettlementAnalysisPeriodType.MONTH, "2026-06"))
                .willReturn(period);
        given(repository.aggregate(actorId, range(period))).willThrow(failure);

        assertThatThrownBy(() -> service.getSummary(
                actorId, SettlementAnalysisPeriodType.MONTH, "2026-06"))
                .isSameAs(failure);
    }

    private SettlementAnalysisPeriod period(
            SettlementAnalysisPeriodType type,
            String requestedPeriod,
            LocalDate start,
            LocalDate end,
            boolean partial) {
        return new SettlementAnalysisPeriod(
                type,
                requestedPeriod,
                start,
                end,
                end,
                LocalDate.of(2026, 7, 19),
                partial);
    }

    private AnalysisAggregate aggregate(long saleCount, String payoutAmount) {
        return new AnalysisAggregate(
                saleCount,
                1,
                new BigDecimal("100"),
                new BigDecimal("10"),
                new BigDecimal("15"),
                new BigDecimal("1.5"),
                new BigDecimal("13.5"),
                new BigDecimal(payoutAmount));
    }

    private AnalysisQueryRange range(SettlementAnalysisPeriod period) {
        return new AnalysisQueryRange(
                period.includedStart(), period.includedEnd(), period.completedThrough());
    }
}
