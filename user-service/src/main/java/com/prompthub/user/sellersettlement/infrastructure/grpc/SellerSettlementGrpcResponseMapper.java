package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.CompareSettlementPeriodsResponse;
import com.prompthub.user.grpc.sellersettlement.CountChange;
import com.prompthub.user.grpc.sellersettlement.DecimalChange;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusResponse;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.GetWeeklySettlementBreakdownResponse;
import com.prompthub.user.grpc.sellersettlement.PayoutStatusCount;
import com.prompthub.user.grpc.sellersettlement.SettlementAggregate;
import com.prompthub.user.grpc.sellersettlement.SettlementAggregateComparison;
import com.prompthub.user.grpc.sellersettlement.SettlementPeriodType;
import com.prompthub.user.grpc.sellersettlement.WeeklyPayoutStatus;
import com.prompthub.user.grpc.sellersettlement.WeeklySettlementBucket;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult.SettlementAggregateChanges;
import com.prompthub.user.sellersettlement.application.dto.WeeklySettlementBreakdownResult;
import com.prompthub.user.sellersettlement.application.service.SettlementChangeCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementGrpcResponseMapper {

    public GetSettlementSummaryResponse toSummary(SettlementAnalysisResult result) {
        return GetSettlementSummaryResponse.newBuilder()
                .setPeriodType(toProtoPeriodType(result.periodType()))
                .setRequestedPeriod(result.requestedPeriod())
                .setAggregate(toAggregate(result))
                .build();
    }

    public CompareSettlementPeriodsResponse toComparison(
            SettlementComparisonResult result) {
        return CompareSettlementPeriodsResponse.newBuilder()
                .setPeriodType(toProtoPeriodType(result.periodType()))
                .setCurrentPeriod(result.currentPeriod())
                .setComparisonPeriod(result.comparisonPeriod())
                .setCurrent(toAggregate(result.current()))
                .setComparison(toAggregate(result.comparison()))
                .setChanges(toChanges(result.changes()))
                .build();
    }

    public GetWeeklySettlementBreakdownResponse toWeeklyBreakdown(
            WeeklySettlementBreakdownResult result) {
        return GetWeeklySettlementBreakdownResponse.newBuilder()
                .setRequestedMonth(result.requestedMonth().toString())
                .setPartial(result.partial())
                .setDataThrough(date(result.dataThrough()))
                .addAllWeeks(result.weeks().stream()
                        .map(week -> WeeklySettlementBucket.newBuilder()
                                .setWeekStartDate(date(week.weekStart()))
                                .setWeekEndDate(date(week.weekEnd()))
                                .setBoundaryWeek(week.boundaryWeek())
                                .setAggregate(toAggregate(week.aggregate()))
                                .build())
                        .toList())
                .build();
    }

    public GetPayoutStatusResponse toPayoutStatus(PayoutStatusResult result) {
        return GetPayoutStatusResponse.newBuilder()
                .setSettlementMonth(result.settlementMonth().toString())
                .addAllStatusCounts(result.statusCounts().stream()
                        .map(count -> PayoutStatusCount.newBuilder()
                                .setStatus(count.status().name())
                                .setCount(count.count())
                                .build())
                        .toList())
                .addAllWeeklySettlements(result.weeklySettlements().stream()
                        .map(row -> WeeklyPayoutStatus.newBuilder()
                                .setPeriodStartDate(date(row.periodStart()))
                                .setPeriodEndDate(date(row.periodEnd()))
                                .setStatus(row.status().name())
                                .setPaidAt(row.paidAt() == null ? "" : row.paidAt().toString())
                                .build())
                        .toList())
                .build();
    }

    private SettlementAggregate toAggregate(SettlementAnalysisResult result) {
        return SettlementAggregate.newBuilder()
                .setIncludedStartDate(date(result.includedStart()))
                .setIncludedEndDate(date(result.includedEnd()))
                .setDataThrough(date(result.dataThrough()))
                .setPartial(result.partial())
                .setSaleCount(result.saleCount())
                .setRefundCount(result.refundCount())
                .setGrossSaleAmount(decimal(result.grossSaleAmount()))
                .setGrossRefundAmount(decimal(result.grossRefundAmount()))
                .setSaleFeeAmount(decimal(result.saleFeeAmount()))
                .setRefundedFeeAmount(decimal(result.refundedFeeAmount()))
                .setNetFeeAmount(decimal(result.netFeeAmount()))
                .setPayoutAmount(decimal(result.payoutAmount()))
                .build();
    }

    private SettlementAggregateComparison toChanges(SettlementAggregateChanges changes) {
        return SettlementAggregateComparison.newBuilder()
                .setSaleCount(toCountChange(changes.saleCount()))
                .setRefundCount(toCountChange(changes.refundCount()))
                .setGrossSaleAmount(toDecimalChange(changes.grossSaleAmount()))
                .setGrossRefundAmount(toDecimalChange(changes.grossRefundAmount()))
                .setSaleFeeAmount(toDecimalChange(changes.saleFeeAmount()))
                .setRefundedFeeAmount(toDecimalChange(changes.refundedFeeAmount()))
                .setNetFeeAmount(toDecimalChange(changes.netFeeAmount()))
                .setPayoutAmount(toDecimalChange(changes.payoutAmount()))
                .build();
    }

    private CountChange toCountChange(SettlementChangeCalculator.CountChange change) {
        return CountChange.newBuilder()
                .setDifference(change.difference())
                .setChangeRatePercent(change.changeRatePercent())
                .setComparable(change.comparable())
                .build();
    }

    private DecimalChange toDecimalChange(SettlementChangeCalculator.DecimalChange change) {
        return DecimalChange.newBuilder()
                .setDifference(decimal(change.difference()))
                .setChangeRatePercent(change.changeRatePercent())
                .setComparable(change.comparable())
                .build();
    }

    private SettlementPeriodType toProtoPeriodType(SettlementAnalysisPeriodType type) {
        return switch (type) {
            case MONTH -> SettlementPeriodType.MONTH;
            case WEEK -> SettlementPeriodType.WEEK;
        };
    }

    private String decimal(BigDecimal value) {
        Objects.requireNonNull(value);
        return value.scale() < 0
                ? value.setScale(0).toPlainString()
                : value.toPlainString();
    }

    private String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }
}
