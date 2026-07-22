package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult.PayoutStatusCountResult;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult.WeeklyPayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriod;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult.SettlementAggregateChanges;
import com.prompthub.user.sellersettlement.application.dto.WeeklySettlementBreakdownResult;
import com.prompthub.user.sellersettlement.application.dto.WeeklySettlementBreakdownResult.WeeklySettlementBucketResult;
import com.prompthub.user.sellersettlement.application.service.SettlementAnalysisPeriodResolver.ComparisonPeriods;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementAnalysisUseCase;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisQueryRange;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusSnapshot;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.WeeklyAnalysisAggregate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerSettlementAnalysisApplicationService
        implements SellerSettlementAnalysisUseCase {

    private final SellerSettlementAnalysisQueryRepository repository;
    private final SettlementAnalysisPeriodResolver periodResolver;
    private final SettlementChangeCalculator changeCalculator;

    @Override
    public SettlementAnalysisResult getSummary(
            UUID actorId,
            SettlementAnalysisPeriodType type,
            String period) {
        SettlementAnalysisPeriod resolved = periodResolver.resolve(type, period);
        return SettlementAnalysisResult.from(
                resolved, repository.aggregate(actorId, toQueryRange(resolved)));
    }

    @Override
    public SettlementComparisonResult compare(
            UUID actorId,
            SettlementAnalysisPeriodType type,
            String currentPeriod,
            String comparisonPeriod) {
        ComparisonPeriods resolved = periodResolver.resolveComparison(
                type, currentPeriod, comparisonPeriod);
        AnalysisAggregate currentAggregate = repository.aggregate(
                actorId, toQueryRange(resolved.current()));
        AnalysisAggregate comparisonAggregate = repository.aggregate(
                actorId, toQueryRange(resolved.comparison()));
        return new SettlementComparisonResult(
                type,
                currentPeriod,
                comparisonPeriod,
                SettlementAnalysisResult.from(resolved.current(), currentAggregate),
                SettlementAnalysisResult.from(resolved.comparison(), comparisonAggregate),
                changes(currentAggregate, comparisonAggregate));
    }

    @Override
    public WeeklySettlementBreakdownResult getWeeklyBreakdown(
            UUID actorId,
            YearMonth month) {
        SettlementAnalysisPeriod resolved = periodResolver.resolve(
                SettlementAnalysisPeriodType.MONTH, month.toString());
        List<WeeklySettlementBucketResult> weeks = repository.findWeeklyBreakdown(
                        actorId,
                        month,
                        resolved.includedEnd(),
                        resolved.completedThrough()).stream()
                .map(weekly -> toWeeklyBucket(weekly, resolved.completedThrough()))
                .toList();
        return new WeeklySettlementBreakdownResult(
                month, resolved.partial(), resolved.dataThrough(), weeks);
    }

    @Override
    public PayoutStatusResult getPayoutStatus(UUID actorId, YearMonth month) {
        periodResolver.resolve(SettlementAnalysisPeriodType.MONTH, month.toString());
        PayoutStatusSnapshot snapshot = repository.findPayoutStatuses(actorId, month);
        return new PayoutStatusResult(
                month,
                snapshot.counts().stream()
                        .map(count -> new PayoutStatusCountResult(count.status(), count.count()))
                        .toList(),
                snapshot.weeklySettlements().stream()
                        .map(row -> new WeeklyPayoutStatusResult(
                                row.periodStart(), row.periodEnd(), row.status(), row.paidAt()))
                        .toList());
    }

    private SettlementAggregateChanges changes(
            AnalysisAggregate current,
            AnalysisAggregate comparison) {
        return new SettlementAggregateChanges(
                changeCalculator.countChange(current.saleCount(), comparison.saleCount()),
                changeCalculator.countChange(current.refundCount(), comparison.refundCount()),
                changeCalculator.decimalChange(
                        current.grossSaleAmount(), comparison.grossSaleAmount()),
                changeCalculator.decimalChange(
                        current.grossRefundAmount(), comparison.grossRefundAmount()),
                changeCalculator.decimalChange(
                        current.saleFeeAmount(), comparison.saleFeeAmount()),
                changeCalculator.decimalChange(
                        current.refundedFeeAmount(), comparison.refundedFeeAmount()),
                changeCalculator.decimalChange(current.netFeeAmount(), comparison.netFeeAmount()),
                changeCalculator.decimalChange(current.payoutAmount(), comparison.payoutAmount()));
    }

    private AnalysisQueryRange toQueryRange(SettlementAnalysisPeriod period) {
        return new AnalysisQueryRange(
                period.includedStart(), period.includedEnd(), period.completedThrough());
    }

    private WeeklySettlementBucketResult toWeeklyBucket(
            WeeklyAnalysisAggregate weekly,
            LocalDate completedThrough) {
        SettlementAnalysisPeriod bucketPeriod = new SettlementAnalysisPeriod(
                SettlementAnalysisPeriodType.WEEK,
                weekly.weekStart().toString(),
                weekly.includedStart(),
                weekly.includedEnd(),
                weekly.includedEnd(),
                completedThrough,
                weekly.boundaryWeek());
        return new WeeklySettlementBucketResult(
                weekly.weekStart(),
                weekly.weekEnd(),
                weekly.boundaryWeek(),
                SettlementAnalysisResult.from(bucketPeriod, weekly.aggregate()));
    }
}
