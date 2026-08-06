package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.SettlementJobStatusResult;
import java.util.Optional;

public interface SettlementJobQuery {

    Optional<SettlementJobStatusResult> findByJobExecutionId(Long jobExecutionId);
}
