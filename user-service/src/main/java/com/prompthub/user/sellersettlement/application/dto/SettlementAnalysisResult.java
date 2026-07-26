package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementAnalysisResult(
        SettlementAnalysisPeriodType periodType,
        String requestedPeriod,
        LocalDate includedStart,
        LocalDate includedEnd,
        LocalDate dataThrough,
        boolean partial,
        long saleCount,
        long refundCount,
        BigDecimal grossSaleAmount,
        BigDecimal grossRefundAmount,
        BigDecimal saleFeeAmount,
        BigDecimal refundedFeeAmount,
        BigDecimal netFeeAmount,
        BigDecimal payoutAmount) {

    public static SettlementAnalysisResult from(
            SettlementAnalysisPeriod period,
            AnalysisAggregate aggregate) {
        return new SettlementAnalysisResult(
                period.type(),
                period.requestedPeriod(),
                period.includedStart(),
                period.includedEnd(),
                period.dataThrough(),
                period.partial(),
                aggregate.saleCount(),
                aggregate.refundCount(),
                aggregate.grossSaleAmount(),
                aggregate.grossRefundAmount(),
                aggregate.saleFeeAmount(),
                aggregate.refundedFeeAmount(),
                aggregate.netFeeAmount(),
                aggregate.payoutAmount());
    }
}
