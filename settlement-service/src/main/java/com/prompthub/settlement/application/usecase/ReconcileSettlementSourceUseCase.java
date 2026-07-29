package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.domain.model.SettlementPeriod;

public interface ReconcileSettlementSourceUseCase {

    SettlementSourceReconciliationResult reconcile(SettlementPeriod period);
}
