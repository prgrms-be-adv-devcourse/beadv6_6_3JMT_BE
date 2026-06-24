package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementJpaRepository extends JpaRepository<Settlement, UUID> {

    List<Settlement> findBySettlementBatchId(UUID settlementBatchId);

    @Query("""
            select coalesce(sum(s.settlementTotalAmount), 0)
            from Settlement s
            where s.sellerId = :sellerId
              and s.payoutStatus = :payoutStatus
            """)
    BigDecimal sumSettlementTotalBySellerIdAndPayoutStatus(@Param("sellerId") UUID sellerId,
                                                           @Param("payoutStatus") PayoutStatus payoutStatus);
}
