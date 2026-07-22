package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import java.util.UUID;

public interface SettlementJobRestarter {

    SettlementJobResult restart(UUID batchId, long jobInstanceId);
}
