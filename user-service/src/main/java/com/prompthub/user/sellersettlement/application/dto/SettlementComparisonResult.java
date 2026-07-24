package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.application.service.SettlementChangeCalculator.CountChange;
import com.prompthub.user.sellersettlement.application.service.SettlementChangeCalculator.DecimalChange;

public record SettlementComparisonResult(
        SettlementAnalysisPeriodType periodType,
        String currentPeriod,
        String comparisonPeriod,
        SettlementAnalysisResult current,
        SettlementAnalysisResult comparison,
        SettlementAggregateChanges changes) {

    public record SettlementAggregateChanges(
            CountChange saleCount,
            CountChange refundCount,
            DecimalChange grossSaleAmount,
            DecimalChange grossRefundAmount,
            DecimalChange saleFeeAmount,
            DecimalChange refundedFeeAmount,
            DecimalChange netFeeAmount,
            DecimalChange payoutAmount) {
    }
}
