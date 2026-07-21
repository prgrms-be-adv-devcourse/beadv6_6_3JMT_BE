package com.prompthub.user.sellersettlement.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "판매자 정산 금액 요약 응답")
public record SellerSettlementSummaryResponse(
        @Schema(description = "누적 총 거래액", example = "10449800")
        BigDecimal totalRevenueAmount,

        @Schema(description = "누적 정산 지급 완료 금액", example = "170000")
        BigDecimal totalSettlementAmount
) {

    public static SellerSettlementSummaryResponse of(
            BigDecimal totalRevenueAmount, BigDecimal totalSettlementAmount) {
        return new SellerSettlementSummaryResponse(totalRevenueAmount, totalSettlementAmount);
    }
}
