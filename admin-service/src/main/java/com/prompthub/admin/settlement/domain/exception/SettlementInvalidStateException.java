package com.prompthub.admin.settlement.domain.exception;

import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;

public class SettlementInvalidStateException extends RuntimeException {

	public SettlementInvalidStateException(
		String action,
		SettlementStatus settlementStatus,
		PayoutStatus payoutStatus
	) {
		super("정산 상태 전이 불가: action=" + action
			+ ", settlementStatus=" + settlementStatus
			+ ", payoutStatus=" + payoutStatus);
	}
}
