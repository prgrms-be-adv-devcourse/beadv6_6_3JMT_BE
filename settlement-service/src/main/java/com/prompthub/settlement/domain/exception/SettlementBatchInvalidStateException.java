package com.prompthub.settlement.domain.exception;

import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;

public class SettlementBatchInvalidStateException extends RuntimeException {

	public SettlementBatchInvalidStateException(SettlementBatchStatus current) {
		super("정산 배치가 처리 중(PROCESSING) 상태가 아닙니다. current=" + current);
	}
}
