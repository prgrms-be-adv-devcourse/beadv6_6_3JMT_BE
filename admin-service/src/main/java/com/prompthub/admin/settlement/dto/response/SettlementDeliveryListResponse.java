package com.prompthub.admin.settlement.dto.response;

import com.prompthub.admin.settlement.entity.SettlementDelivery;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Schema(description = "어드민 정산 전달 목록 응답")
public record SettlementDeliveryListResponse(
        @Schema(description = "정산 전달 항목")
        List<Item> items,

        @Schema(description = "전체 전달 건수", example = "12")
        long totalElements,

        @Schema(description = "0-base 페이지 번호", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size) {

    public static SettlementDeliveryListResponse from(
            List<SettlementDelivery> deliveries,
            long totalElements,
            int page,
            int size,
            Set<UUID> activeDeliveryIds) {
        return new SettlementDeliveryListResponse(
                deliveries.stream()
                        .map(delivery -> Item.from(
                                delivery,
                                activeDeliveryIds.contains(
                                        delivery.getSettlementDeliveryId())))
                        .toList(),
                totalElements,
                page,
                size);
    }

    @Schema(description = "정산 전달 항목")
    public record Item(
            @Schema(description = "정산 전달 레코드 ID(UUID)")
            UUID settlementDeliveryId,

            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "멱등 전달 요청 ID(UUID)")
            UUID deliveryRequestId,

            @Schema(description = "전달 상태", example = "DELIVERY_FAILED")
            String status,

            @Schema(description = "누적 전달 시도 횟수", example = "3")
            int attemptCount,

            @Schema(description = "상태 사유", nullable = true)
            String statusReason,

            @Schema(description = "최초 시도 시각", nullable = true)
            LocalDateTime firstAttemptAt,

            @Schema(description = "최근 시도 시각", nullable = true)
            LocalDateTime lastAttemptAt,

            @Schema(description = "대사 완료 시각", nullable = true)
            LocalDateTime reconciledAt,

            @Schema(description = "수동 재전송 Job 실행 여부")
            boolean retryInProgress,

            @Schema(description = "현재 가능한 관리자 액션", example = "[\"RETRY\"]")
            List<String> availableActions) {

        private static Item from(
                SettlementDelivery delivery,
                boolean retryInProgress) {
            List<String> actions = delivery.canRetry() && !retryInProgress
                    ? List.of("RETRY")
                    : List.of();
            return new Item(
                    delivery.getSettlementDeliveryId(),
                    delivery.getSettlementId(),
                    delivery.getDeliveryRequestId(),
                    delivery.getStatus().name(),
                    delivery.getAttemptCount(),
                    delivery.getStatusReason(),
                    delivery.getFirstAttemptAt(),
                    delivery.getLastAttemptAt(),
                    delivery.getReconciledAt(),
                    retryInProgress,
                    actions);
        }
    }
}
