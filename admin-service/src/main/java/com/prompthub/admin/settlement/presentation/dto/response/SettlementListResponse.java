package com.prompthub.admin.settlement.presentation.dto.response;

import com.prompthub.admin.settlement.infrastructure.persistence.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.infrastructure.persistence.SettlementMonthlyQueryRepository.MonthlyPage;
import com.prompthub.admin.settlement.infrastructure.persistence.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementMonthlyResponse.StatusCount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "어드민 월별 정산 목록 응답")
public record SettlementListResponse(
        @Schema(description = "판매자-월 정산 항목")
        List<Item> items,

        @Schema(description = "전체 판매자-월 그룹 수", example = "16")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size) {

    public static SettlementListResponse from(
            MonthlyPage page,
            List<MonthlyStatusCount> counts,
            Map<UUID, String> sellerNames,
            int pageNumber,
            int size) {
        List<Item> items = page.content().stream()
                .map(aggregate -> Item.from(aggregate, counts, sellerNames))
                .toList();
        return new SettlementListResponse(
                items, page.totalElements(), pageNumber, size);
    }

    @Schema(description = "판매자-월 정산 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            @Schema(description = "판매자명", nullable = true)
            String sellerName,

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
                MonthlyAggregate aggregate,
                List<MonthlyStatusCount> counts,
                Map<UUID, String> sellerNames) {
            return new Item(
                    aggregate.key().sellerId(),
                    sellerNames.get(aggregate.key().sellerId()),
                    aggregate.key().settlementMonth().toString(),
                    aggregate.weeklySettlementCount(),
                    aggregate.aggregatedSettlementCount(),
                    aggregate.salesCount(),
                    aggregate.grossAmount(),
                    aggregate.feeAmount(),
                    aggregate.refundAmount(),
                    aggregate.payoutAmount(),
                    SettlementMonthlyResponse.statusCounts(
                            aggregate.key(), counts));
        }
    }
}
