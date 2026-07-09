package com.prompthub.admin.settlement.domain.model.enums;

public enum SettlementDisplayStatus {

	WAITING("대기"),
	APPROVAL_ON_HOLD("승인 보류"),
	APPROVED("승인"),
	PAYOUT_REQUESTED("지급 신청"),
	PAYOUT_ON_HOLD("지급 보류"),
	PAID("지급 완료"),
	CANCELLED("취소");

	private final String label;

	SettlementDisplayStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

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

	public SettlementDisplayStatus toCard() {
		return switch (this) {
			case WAITING, APPROVAL_ON_HOLD -> WAITING;
			case APPROVED, PAYOUT_REQUESTED -> APPROVED;
			case PAYOUT_ON_HOLD -> PAYOUT_ON_HOLD;
			case PAID -> PAID;
			case CANCELLED -> null;
		};
	}
}
