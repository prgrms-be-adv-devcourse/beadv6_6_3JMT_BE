package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.application.dto.SettlementListResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 정산 목록(페이징) 응답.
 *
 * <p>씨앗(seed)이다. 항목 필드는 목록 세션이 확정한다. (공유 골격 설계 문서 §4-2)
 */
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

    @Schema(description = "정산 목록 항목")
    public record Item(
            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            // TODO(목록 세션): 판매자명/상점명은 이번 범위 제외. 추후 이벤트로 타 서비스에 정보 요청해 채운다. (설계 문서 §7)
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
    }

    public static SettlementListResponse from(SettlementListResult result) {
        List<Item> items = result.items().stream()
                .map(item -> new Item(
                        item.settlementId(),
                        item.sellerId(),
                        null,
                        item.periodStart(),
                        item.periodEnd(),
                        item.productCount(),
                        item.totalAmount(),
                        item.feeTotalAmount(),
                        item.settlementTotalAmount(),
                        item.displayStatus().name()))
                .toList();
        return new SettlementListResponse(items, result.totalElements(), result.page(), result.size());
    }
}
