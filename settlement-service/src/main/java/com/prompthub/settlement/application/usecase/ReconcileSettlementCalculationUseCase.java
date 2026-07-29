package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementCalculationReconciliationReport;
import java.util.UUID;

public interface ReconcileSettlementCalculationUseCase {

    SettlementCalculationReconciliationReport reconcile(UUID settlementBatchId);
}
