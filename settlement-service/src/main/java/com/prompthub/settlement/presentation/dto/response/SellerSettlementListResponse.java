package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Schema(description = "판매자 정산 내역(페이징) 응답")
public record SellerSettlementListResponse(
        @Schema(description = "정산 내역 항목")
        List<Item> items,

        @Schema(description = "전체 항목 수", example = "4")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "10")
        int size
) {

    public static SellerSettlementListResponse from(List<Settlement> settlements, long totalElements, int page, int size) {
        List<Item> items = settlements.stream().map(Item::from).toList();
        return new SellerSettlementListResponse(items, totalElements, page, size);
    }

    @Schema(description = "판매자 정산 내역 항목")
    public record Item(
            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "정산 기준 월(YYYY-MM)", example = "2026-06")
            String period,

            @Schema(description = "정산 기간 시작", example = "2026-06-01")
            LocalDate periodStart,

            @Schema(description = "정산 기간 종료", example = "2026-06-30")
            LocalDate periodEnd,

            @Schema(description = "판매 건수", example = "22")
            int salesCount,

            @Schema(description = "총 거래액", example = "320000.00")
            BigDecimal grossAmount,

            @Schema(description = "판매 수수료", example = "48000.00")
            BigDecimal feeAmount,

            @Schema(description = "환불 차감액", example = "0.00")
            BigDecimal refundAmount,

            @Schema(description = "기타 조정 금액(현재 미사용, 0 고정)", example = "0")
            BigDecimal adjustmentAmount,

            @Schema(description = "최종 지급 예정/완료 금액", example = "260000.00")
            BigDecimal payoutAmount,

            @Schema(description = "표시 상태 코드", example = "WAITING")
            String status,

            @Schema(description = "표시 상태 라벨(한글)", example = "대기")
            String displayStatus,

            @Schema(description = "현재 상태에서 수행 가능한 액션 목록")
            List<Action> availableActions
    ) {

        public static Item from(Settlement settlement) {
            SettlementDisplayStatus display = settlement.displayStatus();
            List<Action> actions = settlement.canRequestPayout()
                    ? List.of(Action.requestPayout())
                    : List.of();
            return new Item(
                    settlement.getId(),
                    YearMonth.from(settlement.getPeriodStart()).toString(),
                    settlement.getPeriodStart(),
                    settlement.getPeriodEnd(),
                    settlement.getProductCount(),
                    settlement.getTotalAmount(),
                    settlement.getFeeTotalAmount(),
                    settlement.getRefundAmount(),
                    BigDecimal.ZERO,
                    settlement.getSettlementTotalAmount(),
                    display.name(),
                    display.getLabel(),
                    actions);
        }
    }

    @Schema(description = "정산 항목에서 수행 가능한 액션")
    public record Action(
            @Schema(description = "액션 타입", example = "REQUEST_PAYOUT")
            String type,

            @Schema(description = "액션 라벨", example = "지급 신청하기")
            String label
    ) {

        public static Action requestPayout() {
            return new Action("REQUEST_PAYOUT", "지급 신청하기");
        }
    }
}
