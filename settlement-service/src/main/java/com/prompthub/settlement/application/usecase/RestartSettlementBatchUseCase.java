package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;

public interface RestartSettlementBatchUseCase {

    SettlementJobResult restart(RestartSettlementBatchCommand command);
}
