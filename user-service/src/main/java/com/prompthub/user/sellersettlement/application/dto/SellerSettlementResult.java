package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record SellerSettlementResult(
        UUID settlementId,
        SettlementDisplayStatus status,
        LocalDateTime approvedAt,
        LocalDateTime payoutRequestedAt,
        LocalDateTime paidAt,
        LocalDateTime cancelledAt
) {

    public static SellerSettlementResult from(SellerSettlement s) {
        return new SellerSettlementResult(
                s.getSettlementId(),
                s.getStatus(),
                s.getApprovedAt(),
                s.getPayoutRequestedAt(),
                s.getPaidAt(),
                s.getCancelledAt());
    }
}
