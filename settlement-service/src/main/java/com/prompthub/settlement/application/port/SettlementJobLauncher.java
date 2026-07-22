package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;

public interface SettlementJobLauncher {

    SettlementJobResult launch(RunSettlementBatchCommand command);
}
