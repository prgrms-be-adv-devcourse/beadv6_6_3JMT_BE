package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.Settlement;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository {

    Settlement save(Settlement settlement);

    List<Settlement> saveAll(List<Settlement> settlements);

    List<Settlement> findBySettlementBatchId(UUID settlementBatchId);

    Optional<Settlement> findById(UUID id);

    BigDecimal sumPaidSettlementAmountBySeller(UUID sellerId);
}
