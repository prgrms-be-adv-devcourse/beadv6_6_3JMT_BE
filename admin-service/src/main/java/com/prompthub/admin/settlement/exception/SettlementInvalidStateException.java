package com.prompthub.admin.settlement.exception;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;

public class SettlementInvalidStateException extends RuntimeException {

	public SettlementInvalidStateException(String action, SettlementDisplayStatus status) {
		super("정산 상태 전이 불가: action=" + action + ", status=" + status);
	}
}
