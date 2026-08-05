package com.prompthub.settlement.domain.model.batch;

public enum SettlementBatchStatus {

	PROCESSING,
	COMPLETED,
	FAILED,
	RECONCILIATION_FAILED,
	RETRY_REQUESTED,
	CANCELLED
}
