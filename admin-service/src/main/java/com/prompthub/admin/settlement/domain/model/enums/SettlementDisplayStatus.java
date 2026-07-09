package com.prompthub.admin.settlement.domain.model.enums;

/**
 * 정산 운영 상태(운영 단일 진실 seller_settlement.status)의 단일 표시 상태. 7값.
 * user-service SellerSettlement 의 SettlementDisplayStatus 와 값이 일치해야 한다(같은 컬럼 매핑).
 */
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

	/**
	 * 요약(summary) 카드 집계용 버킷. 취소는 카드 집계 대상이 아니므로 null.
	 */
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
