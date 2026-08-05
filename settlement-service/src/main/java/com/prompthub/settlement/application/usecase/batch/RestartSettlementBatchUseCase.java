package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;

public interface RestartSettlementBatchUseCase {

    SettlementJobResult restart(RestartSettlementBatchCommand command);
}
