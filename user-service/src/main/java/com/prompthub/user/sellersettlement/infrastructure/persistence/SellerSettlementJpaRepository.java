package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerSettlementJpaRepository extends JpaRepository<SellerSettlement, UUID> {

    boolean existsBySettlementId(UUID settlementId);

    Optional<SellerSettlement> findBySettlementId(UUID settlementId);

    @Query("""
            select s from SellerSettlement s
            where s.sellerId = :sellerId
              and (:status is null or s.status = :status)
              and (:periodStart is null or s.periodStart >= :periodStart)
              and (:periodEnd is null or s.periodStart <= :periodEnd)
            """)
    Page<SellerSettlement> findPageBySeller(
            @Param("sellerId") UUID sellerId,
            @Param("status") SettlementDisplayStatus status,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd,
            Pageable pageable);
}
