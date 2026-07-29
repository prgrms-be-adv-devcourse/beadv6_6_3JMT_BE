package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.SettlementDelivery;
import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SettlementDeliveryRepository {

    SettlementDelivery save(SettlementDelivery delivery);

    Optional<SettlementDelivery> findById(UUID id);

    Optional<SettlementDelivery> findBySettlementId(UUID settlementId);

    void deleteBySettlementIds(List<UUID> settlementIds);

    List<SettlementDelivery> findCalculatedByBatchId(UUID settlementBatchId);

    Map<SettlementDeliveryStatus, Long> countByStatus(UUID settlementBatchId);
}
