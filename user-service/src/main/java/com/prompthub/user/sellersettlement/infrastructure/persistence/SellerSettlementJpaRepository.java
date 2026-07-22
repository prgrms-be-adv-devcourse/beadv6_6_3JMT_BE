package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerSettlementJpaRepository extends JpaRepository<SellerSettlement, UUID> {

    boolean existsBySettlementId(UUID settlementId);

    Optional<SellerSettlement> findBySettlementId(UUID settlementId);

    @Query("""
            select s from SellerSettlement s
            where s.sellerId = :sellerId
              and s.periodStart >= :periodStart
              and s.periodStart < :periodEnd
            order by s.periodStart asc, s.sellerSettlementId asc
            """)
    List<SellerSettlement> findWeeklySettlements(
            @Param("sellerId") UUID sellerId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);

    @Query("select coalesce(sum(s.totalAmount), 0) from SellerSettlement s where s.sellerId = :sellerId")
    BigDecimal sumTotalAmountBySeller(@Param("sellerId") UUID sellerId);

    @Query("""
            select coalesce(sum(s.settlementTotalAmount), 0) from SellerSettlement s
            where s.sellerId = :sellerId and s.status = :status
            """)
    BigDecimal sumSettlementTotalAmountBySellerAndStatus(
            @Param("sellerId") UUID sellerId, @Param("status") SettlementDisplayStatus status);
}
