package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.domain.model.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "정산 목록(페이징) 응답")
public record SettlementListResponse(
        @Schema(description = "정산 목록 항목")
        List<Item> items,

        @Schema(description = "전체 항목 수", example = "16")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size
) {

    public static SettlementListResponse from(
            List<Settlement> settlements, Map<UUID, String> sellerNames,
            long totalElements, int page, int size) {
        List<Item> items = settlements.stream()
                .map(settlement -> Item.from(settlement, sellerNames.get(settlement.getSellerId())))
                .toList();
        return new SettlementListResponse(items, totalElements, page, size);
    }

    @Schema(description = "정산 목록 항목")
    public record Item(
            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            @Schema(description = "판매자명(상점명). User 서비스 동기 조회로 채우며, 조회되지 않으면 null", nullable = true)
            String sellerName,

            @Schema(description = "정산 기간 시작", example = "2026-06-01")
            LocalDate periodStart,

            @Schema(description = "정산 기간 종료", example = "2026-06-30")
            LocalDate periodEnd,

            @Schema(description = "판매 건수", example = "37")
            int productCount,

            @Schema(description = "총 거래액", example = "540000.00")
            BigDecimal totalAmount,

            @Schema(description = "수수료", example = "81000.00")
            BigDecimal feeTotalAmount,

            @Schema(description = "지급액", example = "459000.00")
            BigDecimal settlementTotalAmount,

            @Schema(description = "표시 상태", example = "WAITING")
            String displayStatus,

            @Schema(description = "정산 산출(배치 실행) 시각", example = "2026-07-01T02:00:00")
            LocalDateTime calculatedAt
    ) {

        public static Item from(Settlement settlement, String sellerName) {
            return new Item(
                    settlement.getId(),
                    settlement.getSellerId(),
                    sellerName,
                    settlement.getPeriodStart(),
                    settlement.getPeriodEnd(),
                    settlement.getProductCount(),
                    settlement.getTotalAmount(),
                    settlement.getFeeTotalAmount(),
                    settlement.getSettlementTotalAmount(),
                    settlement.displayStatus().name(),
                    settlement.getCalculatedAt());
        }
    }
}
