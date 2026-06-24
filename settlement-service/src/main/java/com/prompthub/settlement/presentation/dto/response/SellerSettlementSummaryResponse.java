package com.prompthub.settlement.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "판매자 정산 요약 응답")
public record SellerSettlementSummaryResponse(
        @Schema(description = "등록한 프롬프트 수", example = "3")
        int registeredPromptCount,

        @Schema(description = "누적 판매 건수", example = "1342")
        long totalSalesCount,

        @Schema(description = "누적 총 거래액", example = "10449800")
        BigDecimal totalRevenueAmount,

        @Schema(description = "누적 정산 지급 완료 금액", example = "170000")
        BigDecimal totalSettlementAmount
) {

    public static SellerSettlementSummaryResponse of(
            int registeredPromptCount,
            long totalSalesCount,
            BigDecimal totalRevenueAmount,
            BigDecimal totalSettlementAmount
    ) {
        return new SellerSettlementSummaryResponse(
                registeredPromptCount, totalSalesCount, totalRevenueAmount, totalSettlementAmount);
    }
}
