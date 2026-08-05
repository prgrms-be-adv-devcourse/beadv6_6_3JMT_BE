package com.prompthub.settlement.application.usecase.calculation;

import com.prompthub.settlement.application.dto.calculation.SettlementCalculationReconciliationReport;
import java.util.UUID;

public interface ReconcileSettlementCalculationUseCase {

    SettlementCalculationReconciliationReport reconcile(UUID settlementBatchId);
}
