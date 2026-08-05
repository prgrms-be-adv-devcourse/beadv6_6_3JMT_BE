package com.prompthub.settlement.application.usecase.source;

import com.prompthub.settlement.application.dto.source.SettlementSourceReconciliationResult;
import com.prompthub.settlement.domain.model.batch.SettlementPeriod;

public interface ReconcileSettlementSourceUseCase {

    SettlementSourceReconciliationResult reconcile(SettlementPeriod period);
}
