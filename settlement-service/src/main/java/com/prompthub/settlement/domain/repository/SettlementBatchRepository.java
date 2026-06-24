package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.SettlementBatch;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository {

    SettlementBatch save(SettlementBatch settlementBatch);

    Optional<SettlementBatch> findById(UUID batchId);
}
