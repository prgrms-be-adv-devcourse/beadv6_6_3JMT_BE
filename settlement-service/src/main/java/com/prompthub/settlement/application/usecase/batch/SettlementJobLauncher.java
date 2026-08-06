package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;

public interface SettlementJobLauncher {

    SettlementJobResult launch(RunSettlementBatchCommand command);
}
