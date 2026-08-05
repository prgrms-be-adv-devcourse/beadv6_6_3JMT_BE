package com.prompthub.settlement.application.service.batch;

import com.prompthub.settlement.application.dto.batch.SettlementJobStatusResult;
import com.prompthub.settlement.application.usecase.batch.GetSettlementJobStatusUseCase;
import com.prompthub.settlement.application.usecase.batch.SettlementJobQuery;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetSettlementJobStatusApplicationService
        implements GetSettlementJobStatusUseCase {

    private final SettlementJobQuery settlementJobQuery;

    @Override
    public SettlementJobStatusResult getStatus(Long jobExecutionId) {
        return settlementJobQuery.findByJobExecutionId(jobExecutionId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }
}
