package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;

public interface RunSettlementBatchUseCase {

    SettlementJobResult run(RunSettlementJobCommand command);
}
