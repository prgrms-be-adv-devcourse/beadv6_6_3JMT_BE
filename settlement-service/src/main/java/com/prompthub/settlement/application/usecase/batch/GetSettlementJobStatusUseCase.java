package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.SettlementJobStatusResult;

public interface GetSettlementJobStatusUseCase {

    SettlementJobStatusResult getStatus(Long jobExecutionId);
}
