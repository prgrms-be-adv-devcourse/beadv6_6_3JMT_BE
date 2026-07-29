package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SettlementDeliveryQueryJpaRepository
        extends Repository<SettlementDelivery, UUID> {

    @Query("""
            select delivery
            from SettlementDelivery delivery
            where (:status is null or delivery.status = :status)
              and (:problemOnly = false
                   or delivery.status in (
                       com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus.DELIVERY_FAILED,
                       com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus.MISMATCH))
              and (:identifier is null
                   or delivery.settlementId = :identifier
                   or delivery.deliveryRequestId = :identifier)
            order by
                case when delivery.lastAttemptAt is null then 1 else 0 end,
                delivery.lastAttemptAt desc,
                delivery.createdAt desc,
                delivery.settlementDeliveryId desc
            """)
    Page<SettlementDelivery> findPage(
            @Param("status") SettlementDeliveryStatus status,
            @Param("problemOnly") boolean problemOnly,
            @Param("identifier") UUID identifier,
            Pageable pageable);

    @Query("""
            select new com.prompthub.admin.settlement.repository.SettlementDeliveryStatusCount(
                delivery.status, count(delivery))
            from SettlementDelivery delivery
            group by delivery.status
            """)
    List<SettlementDeliveryStatusCount> countByStatus();

    Optional<SettlementDelivery> findById(UUID settlementDeliveryId);
}
