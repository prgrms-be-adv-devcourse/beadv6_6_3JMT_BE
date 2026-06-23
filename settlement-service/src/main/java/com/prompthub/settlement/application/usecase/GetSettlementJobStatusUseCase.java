package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;

public interface GetSettlementJobStatusUseCase {

    SettlementJobStatusResult getStatus(Long jobExecutionId);
}
