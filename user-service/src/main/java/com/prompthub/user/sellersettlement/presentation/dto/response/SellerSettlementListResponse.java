package com.prompthub.user.sellersettlement.presentation.dto.response;

import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyPage;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementMonthlyResponse.StatusCount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "판매자 월별 정산 목록 응답")
public record SellerSettlementListResponse(
        @Schema(description = "월별 정산 항목")
        List<Item> items,

        @Schema(description = "전체 월 수", example = "4")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "10")
        int size) {

    public static SellerSettlementListResponse from(
            MonthlyPage monthlyPage,
            List<MonthlyStatusCount> counts,
            int page,
            int size) {
        List<Item> items = monthlyPage.content().stream()
                .map(aggregate -> Item.from(aggregate, counts))
                .toList();
        return new SellerSettlementListResponse(
                items, monthlyPage.totalElements(), page, size);
    }

    @Schema(description = "판매자 월별 정산 항목")
    public record Item(
            @Schema(description = "정산 월(YYYY-MM)", example = "2026-07")
            String settlementMonth,

            @Schema(description = "월에 포함된 전체 주간 정산 건수", example = "4")
            long weeklySettlementCount,

            @Schema(description = "합계에 반영된 비취소 주간 정산 건수", example = "3")
            long aggregatedSettlementCount,

            @Schema(description = "비취소 판매 건수 합계", example = "22")
            long salesCount,

            @Schema(description = "비취소 총 거래액", example = "2200000.00")
            BigDecimal grossAmount,

            @Schema(description = "비취소 판매 수수료", example = "330000.00")
            BigDecimal feeAmount,

            @Schema(description = "비취소 환불 차감액", example = "100000.00")
            BigDecimal refundAmount,

            @Schema(description = "비취소 지급 예정 또는 완료 금액", example = "1770000.00")
            BigDecimal payoutAmount,

            @Schema(description = "주간 정산 상태별 건수")
            List<StatusCount> statusCounts) {

        static Item from(
                MonthlyAggregate aggregate, List<MonthlyStatusCount> allCounts) {
            return new Item(
                    aggregate.key().settlementMonth().toString(),
                    aggregate.weeklySettlementCount(),
                    aggregate.aggregatedSettlementCount(),
                    aggregate.salesCount(),
                    aggregate.grossAmount(),
                    aggregate.feeAmount(),
                    aggregate.refundAmount(),
                    aggregate.payoutAmount(),
                    SellerSettlementMonthlyResponse.statusCounts(
                            aggregate.key(), allCounts));
        }
    }
}
