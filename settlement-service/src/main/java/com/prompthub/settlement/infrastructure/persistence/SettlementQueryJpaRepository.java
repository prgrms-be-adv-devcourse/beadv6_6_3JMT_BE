package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SettlementQueryJpaRepository
        extends Repository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {

    @Query("""
            select new com.prompthub.settlement.domain.repository.SettlementStatusAggregate(
                s.settlementStatus, s.payoutStatus, sum(s.settlementTotalAmount), count(s))
            from Settlement s
            group by s.settlementStatus, s.payoutStatus
            """)
    List<SettlementStatusAggregate> aggregateByStatus();
}
