package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.port.SettlementJobQuery;
import com.prompthub.settlement.application.usecase.GetSettlementJobStatusUseCase;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetSettlementJobStatusApplicationService implements GetSettlementJobStatusUseCase {

    private final SettlementJobQuery settlementJobQuery;

    @Override
    public SettlementJobStatusResult getStatus(Long jobExecutionId) {
        return settlementJobQuery.findByJobExecutionId(jobExecutionId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }
}
