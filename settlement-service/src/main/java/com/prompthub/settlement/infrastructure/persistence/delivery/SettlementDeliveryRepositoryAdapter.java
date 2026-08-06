package com.prompthub.settlement.infrastructure.persistence.delivery;

import com.prompthub.settlement.domain.model.delivery.SettlementDelivery;
import com.prompthub.settlement.domain.model.delivery.SettlementDeliveryStatus;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementDeliveryRepositoryAdapter
        implements SettlementDeliveryRepository {

    private final SettlementDeliveryJpaRepository jpaRepository;

    @Override
    public SettlementDelivery save(SettlementDelivery delivery) {
        return jpaRepository.save(delivery);
    }

    @Override
    public Optional<SettlementDelivery> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<SettlementDelivery> findBySettlementId(UUID settlementId) {
        return jpaRepository.findBySettlementId(settlementId);
    }

    @Override
    public void deleteBySettlementIds(List<UUID> settlementIds) {
        if (!settlementIds.isEmpty()) {
            jpaRepository.deleteBySettlementIds(settlementIds);
        }
    }

    @Override
    public List<SettlementDelivery> findCalculatedByBatchId(UUID batchId) {
        return jpaRepository.findBySettlementBatchIdAndStatusOrderById(
                batchId, SettlementDeliveryStatus.CALCULATED);
    }

    @Override
    public Map<SettlementDeliveryStatus, Long> countByStatus(UUID batchId) {
        EnumMap<SettlementDeliveryStatus, Long> result =
                new EnumMap<>(SettlementDeliveryStatus.class);
        for (SettlementDeliveryStatus status : SettlementDeliveryStatus.values()) {
            result.put(status,
                    jpaRepository.countBySettlementBatchIdAndStatus(batchId, status));
        }
        return result;
    }
}
