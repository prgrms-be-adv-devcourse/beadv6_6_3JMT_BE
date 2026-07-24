package com.prompthub.order.application.service.order;

public interface OrderExpirationMetrics {

	void recordCandidates(CandidateSource source, int count);

	void recordCompensation(CompensationOutcome outcome);

	enum CandidateSource {
		DB,
		REDIS
	}

	enum CompensationOutcome {
		SUCCESS,
		SKIPPED,
		FAILURE,
		DLQ
	}
}
