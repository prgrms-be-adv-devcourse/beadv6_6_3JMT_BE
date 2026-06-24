package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import java.util.Optional;

public interface SettlementJobQuery {

    Optional<SettlementJobStatusResult> findByJobExecutionId(Long jobExecutionId);
}
