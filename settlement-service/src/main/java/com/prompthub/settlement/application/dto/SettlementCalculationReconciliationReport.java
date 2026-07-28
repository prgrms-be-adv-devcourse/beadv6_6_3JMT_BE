package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import java.util.List;
import java.util.UUID;

public record SettlementCalculationReconciliationReport(
        int totalCount,
        List<UUID> mismatchedSettlementIds
) {

    public SettlementCalculationReconciliationReport {
        mismatchedSettlementIds = List.copyOf(mismatchedSettlementIds);
    }

    public static SettlementCalculationReconciliationReport from(
            List<SettlementCalculationReconciliation> reconciliations) {
        List<UUID> mismatchedIds = reconciliations.stream()
                .filter(reconciliation -> !reconciliation.isMatched())
                .map(SettlementCalculationReconciliation::getSettlementId)
                .toList();
        return new SettlementCalculationReconciliationReport(
                reconciliations.size(), mismatchedIds);
    }

    public boolean matched() {
        return mismatchedSettlementIds.isEmpty();
    }
}
