package com.prompthub.settlement.application.service.batch;

import com.prompthub.settlement.application.dto.batch.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;
import com.prompthub.settlement.application.usecase.batch.RunSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.batch.SettlementJobLauncher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RunSettlementBatchApplicationService implements RunSettlementBatchUseCase {

    private final SettlementJobLauncher settlementJobLauncher;

    @Override
    public SettlementJobResult run(RunSettlementBatchCommand command) {
        return settlementJobLauncher.launch(command);
    }
}
