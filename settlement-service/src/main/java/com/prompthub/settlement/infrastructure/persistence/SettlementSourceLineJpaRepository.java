package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementSourceLine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementSourceLineJpaRepository extends JpaRepository<SettlementSourceLine, UUID> {

    @Query("""
            select distinct l.sellerId
            from SettlementSourceLine l
            where l.settlementId is null
              and l.occurredAt >= :start
              and l.occurredAt < :end
            """)
    List<UUID> findSettleableSellerIds(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select l
            from SettlementSourceLine l
            where l.sellerId = :sellerId
              and l.settlementId is null
              and l.occurredAt >= :start
              and l.occurredAt < :end
            """)
    List<SettlementSourceLine> findSettleableLines(@Param("sellerId") UUID sellerId,
                                                   @Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
