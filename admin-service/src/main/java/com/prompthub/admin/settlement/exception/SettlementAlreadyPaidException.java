package com.prompthub.admin.settlement.exception;

import java.util.UUID;

public class SettlementAlreadyPaidException extends RuntimeException {

	public SettlementAlreadyPaidException(UUID settlementId) {
		super("이미 지급 완료된 정산은 취소할 수 없습니다. settlementId=" + settlementId);
	}
}
