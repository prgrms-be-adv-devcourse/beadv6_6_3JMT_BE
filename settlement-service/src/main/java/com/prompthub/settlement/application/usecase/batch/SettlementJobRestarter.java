package com.prompthub.settlement.application.usecase.batch;

import com.prompthub.settlement.application.dto.batch.SettlementJobResult;
import java.util.UUID;

public interface SettlementJobRestarter {

    SettlementJobResult restart(UUID batchId, long jobInstanceId);
}
