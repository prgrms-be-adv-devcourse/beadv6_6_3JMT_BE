package com.prompthub.admin.settlement.presentation.dto.response;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementWeeklyQueryRepository.WeeklyPage;
import com.prompthub.admin.settlement.domain.repository.SettlementWeeklyStatusCount;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementMonthlyResponse.Action;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementMonthlyResponse.WeeklySettlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "어드민 주간 정산 목록 응답")
public record SettlementWeeklyListResponse(
        @Schema(description = "주간 정산 항목")
        List<Item> items,

        @Schema(description = "선택한 정산 월 범위의 주간 정산 상태별 건수")
        List<StatusCount> statusCounts,

        @Schema(description = "전체 주간 정산 건수", example = "16")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size) {

    public static SettlementWeeklyListResponse from(
            WeeklyPage page,
            List<SettlementWeeklyStatusCount> counts,
            Map<UUID, String> sellerNames,
            int pageNumber,
            int size) {
        List<Item> items = page.content().stream()
                .map(settlement -> Item.from(
                        settlement, sellerNames.get(settlement.getSellerId())))
                .toList();
        return new SettlementWeeklyListResponse(
                items, statusCounts(counts), page.totalElements(), pageNumber, size);
    }

    private static List<StatusCount> statusCounts(List<SettlementWeeklyStatusCount> counts) {
        Map<SettlementDisplayStatus, Long> countByStatus =
                new EnumMap<>(SettlementDisplayStatus.class);
        counts.forEach(count -> countByStatus.put(count.status(), count.count()));
        List<StatusCount> result = new ArrayList<>();
        for (SettlementDisplayStatus status : SettlementDisplayStatus.values()) {
            result.add(new StatusCount(
                    status.name(), status.getLabel(), countByStatus.getOrDefault(status, 0L)));
        }
        return result;
    }

    @Schema(description = "주간 정산 상태별 건수")
    public record StatusCount(
            @Schema(description = "표시 상태 코드", example = "APPROVED")
            String status,

            @Schema(description = "표시 상태 라벨", example = "승인")
            String statusLabel,

            @Schema(description = "해당 상태의 주간 정산 건수", example = "2")
            long count) {
    }

    @Schema(description = "어드민 주간 정산 항목")
    public record Item(
            @Schema(description = "판매자 ID(UUID)")
            UUID sellerId,

            @Schema(description = "판매자명", nullable = true)
            String sellerName,

            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "정산 기간 시작", example = "2026-06-29")
            LocalDate periodStart,

            @Schema(description = "정산 기간 종료", example = "2026-07-05")
            LocalDate periodEnd,

            @Schema(description = "판매 건수", example = "22")
            int salesCount,

            @Schema(description = "총 거래액", example = "2200000.00")
            BigDecimal grossAmount,

            @Schema(description = "판매 수수료", example = "330000.00")
            BigDecimal feeAmount,

            @Schema(description = "환불 차감액", example = "100000.00")
            BigDecimal refundAmount,

            @Schema(description = "지급 예정 또는 완료 금액", example = "1770000.00")
            BigDecimal payoutAmount,

            @Schema(description = "표시 상태 코드", example = "APPROVED")
            String status,

            @Schema(description = "표시 상태 라벨", example = "승인")
            String statusLabel,

            @Schema(description = "정산 계산 시각")
            LocalDateTime calculatedAt,

            @Schema(description = "승인 시각", nullable = true)
            LocalDateTime approvedAt,

            @Schema(description = "지급 신청 시각", nullable = true)
            LocalDateTime payoutRequestedAt,

            @Schema(description = "지급 완료 시각", nullable = true)
            LocalDateTime paidAt,

            @Schema(description = "취소 시각", nullable = true)
            LocalDateTime cancelledAt,

            @Schema(description = "현재 상태에서 수행 가능한 액션")
            List<Action> availableActions) {

        static Item from(Settlement settlement, String sellerName) {
            WeeklySettlement weekly = WeeklySettlement.from(settlement);
            return new Item(
                    settlement.getSellerId(), sellerName,
                    weekly.settlementId(), weekly.periodStart(), weekly.periodEnd(),
                    weekly.salesCount(), weekly.grossAmount(), weekly.feeAmount(),
                    weekly.refundAmount(), weekly.payoutAmount(), weekly.status(), weekly.statusLabel(),
                    weekly.calculatedAt(), weekly.approvedAt(), weekly.payoutRequestedAt(),
                    weekly.paidAt(), weekly.cancelledAt(), weekly.availableActions());
        }
    }
}
