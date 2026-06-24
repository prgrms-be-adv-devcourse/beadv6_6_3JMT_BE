package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.domain.model.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    public static SettlementListResponse from(List<Settlement> settlements, long totalElements, int page, int size) {
        List<Item> items = settlements.stream().map(Item::from).toList();
        return new SettlementListResponse(items, totalElements, page, size);
    }

    @Schema(description = "정산 목록 항목")
    public record Item(
            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            // TODO: 판매자명/상점명은 이번 범위 제외. 추후 이벤트로 타 서비스에 정보 요청해 채운다.
            @Schema(description = "판매자명(추후 이벤트 연동으로 채움, 현재는 null)", nullable = true)
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
            String displayStatus
    ) {

        public static Item from(Settlement settlement) {
            return new Item(
                    settlement.getId(),
                    settlement.getSellerId(),
                    null,
                    settlement.getPeriodStart(),
                    settlement.getPeriodEnd(),
                    settlement.getProductCount(),
                    settlement.getTotalAmount(),
                    settlement.getFeeTotalAmount(),
                    settlement.getSettlementTotalAmount(),
                    settlement.displayStatus().name());
        }
    }
}
