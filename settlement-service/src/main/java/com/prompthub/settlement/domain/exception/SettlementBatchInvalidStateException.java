package com.prompthub.settlement.domain.exception;

import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;

public class SettlementBatchInvalidStateException extends RuntimeException {

	public SettlementBatchInvalidStateException(
		SettlementBatchStatus expected,
		SettlementBatchStatus current
	) {
		super("정산 배치 상태 전이가 올바르지 않습니다. expected=" + expected + ", current=" + current);
	}
}
