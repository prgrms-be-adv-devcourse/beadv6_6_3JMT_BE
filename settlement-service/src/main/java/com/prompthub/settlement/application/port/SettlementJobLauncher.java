package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;

public interface SettlementJobLauncher {

    SettlementJobResult launch(RunSettlementJobCommand command);
}
