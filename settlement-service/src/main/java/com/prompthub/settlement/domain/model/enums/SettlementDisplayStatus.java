package com.prompthub.settlement.domain.model.enums;

public enum SettlementDisplayStatus {

    WAITING,
    APPROVAL_ON_HOLD,
    APPROVED,
    PAYOUT_REQUESTED,
    PAYOUT_ON_HOLD,
    PAID,
    CANCELLED;

    public static SettlementDisplayStatus from(SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
        return switch (settlementStatus) {
            case PENDING_APPROVAL -> WAITING;
            case SETTLEMENT_ON_HOLD -> APPROVAL_ON_HOLD;
            case CANCELLED -> CANCELLED;
            case APPROVED -> switch (payoutStatus) {
                case NOT_READY, READY -> APPROVED;
                case PAYOUT_REQUESTED -> PAYOUT_REQUESTED;
                case PAYOUT_ON_HOLD -> PAYOUT_ON_HOLD;
                case PAID -> PAID;
            };
        };
    }
}
