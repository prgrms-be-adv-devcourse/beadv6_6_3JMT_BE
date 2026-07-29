package com.prompthub.settlement.infrastructure.persistence.delivery;

import com.prompthub.settlement.domain.model.SettlementDelivery;
import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementDeliveryJpaRepository
        extends JpaRepository<SettlementDelivery, UUID> {

    Optional<SettlementDelivery> findBySettlementId(UUID settlementId);

    List<SettlementDelivery> findBySettlementBatchIdAndStatusOrderById(
            UUID settlementBatchId, SettlementDeliveryStatus status);

    long countBySettlementBatchIdAndStatus(
            UUID settlementBatchId, SettlementDeliveryStatus status);
}
