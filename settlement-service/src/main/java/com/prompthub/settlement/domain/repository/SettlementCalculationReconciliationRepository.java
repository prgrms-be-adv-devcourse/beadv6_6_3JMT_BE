package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.calculation.SettlementCalculationReconciliation;
import java.util.List;
import java.util.UUID;

public interface SettlementCalculationReconciliationRepository {

    List<SettlementCalculationReconciliation> saveAll(
            List<SettlementCalculationReconciliation> reconciliations);

    List<SettlementCalculationReconciliation> findBySettlementBatchIdOrderByVerifiedAtAsc(
            UUID settlementBatchId);
}
