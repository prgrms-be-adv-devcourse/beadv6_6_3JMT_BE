package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand.Detail;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementRegistrationMatcher {

    public boolean matches(
            RegisterSellerSettlementCommand expected,
            SellerSettlement actual) {
        if (!expected.settlementId().equals(actual.getSettlementId())
                || !expected.sellerId().equals(actual.getSellerId())
                || !expected.periodStart().equals(actual.getPeriodStart())
                || !expected.periodEnd().equals(actual.getPeriodEnd())
                || expected.productCount() != actual.getProductCount()
                || differs(expected.grossSalesAmount(), actual.getTotalAmount())
                || differs(expected.refundAmount(), actual.getRefundAmount())
                || differs(expected.feeTotalAmount(), actual.getFeeTotalAmount())
                || differs(expected.settlementTotalAmount(), actual.getSettlementTotalAmount())
                || differs(expected.calculatedAt(), actual.getCalculatedAt())
                || expected.details().size() != actual.getDetails().size()) {
            return false;
        }

        Map<java.util.UUID, SellerSettlementDetail> actualDetails =
                actual.getDetails().stream().collect(Collectors.toMap(
                        SellerSettlementDetail::getSettlementDetailId,
                        Function.identity(),
                        (left, right) -> left));
        if (actualDetails.size() != actual.getDetails().size()) {
            return false;
        }
        return expected.details().stream().allMatch(detail ->
                matchesDetail(detail, actualDetails.get(detail.settlementDetailId())));
    }

    private boolean matchesDetail(Detail expected, SellerSettlementDetail actual) {
        return actual != null
                && (actual.getSettlementSourceLineId() == null
                        || expected.settlementSourceLineId()
                                .equals(actual.getSettlementSourceLineId()))
                && expected.orderProductId().equals(actual.getOrderProductId())
                && expected.lineType() == actual.getLineType()
                && !differs(expected.lineAmount(), actual.getLineAmount())
                && !differs(expected.feeRate(), actual.getFeeRate())
                && !differs(expected.feeAmount(), actual.getFeeAmount())
                && !differs(expected.lineSettlementAmount(), actual.getLineSettlementAmount())
                && !differs(expected.occurredAt(), actual.getOccurredAt());
    }

    private boolean differs(BigDecimal expected, BigDecimal actual) {
        return expected == null || actual == null
                ? expected != actual
                : expected.compareTo(actual) != 0;
    }

    private boolean differs(LocalDateTime expected, LocalDateTime actual) {
        return expected == null || actual == null
                ? expected != actual
                : !expected.truncatedTo(ChronoUnit.MICROS)
                        .equals(actual.truncatedTo(ChronoUnit.MICROS));
    }
}
