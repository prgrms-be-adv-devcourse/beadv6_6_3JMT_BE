package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
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
