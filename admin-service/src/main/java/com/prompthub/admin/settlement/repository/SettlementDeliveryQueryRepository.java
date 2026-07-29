package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementDeliveryQueryRepository {

    private final SettlementDeliveryQueryJpaRepository jpaRepository;

    public DeliveryPage findPage(SettlementDeliveryListQuery query) {
        Page<SettlementDelivery> page = jpaRepository.findPage(
                query.status(),
                query.problemOnly(),
                query.identifier(),
                PageRequest.of(query.page(), query.size()));
        return new DeliveryPage(page.getContent(), page.getTotalElements());
    }

    public Map<SettlementDeliveryStatus, Long> countByStatus() {
        EnumMap<SettlementDeliveryStatus, Long> counts =
                new EnumMap<>(SettlementDeliveryStatus.class);
        for (SettlementDeliveryStatus status : SettlementDeliveryStatus.values()) {
            counts.put(status, 0L);
        }
        for (SettlementDeliveryStatusCount count : jpaRepository.countByStatus()) {
            counts.put(count.status(), count.count());
        }
        return counts;
    }

    public Optional<SettlementDelivery> findById(UUID settlementDeliveryId) {
        return jpaRepository.findById(settlementDeliveryId);
    }

    public record DeliveryPage(
            List<SettlementDelivery> content,
            long totalElements) {
    }
}
