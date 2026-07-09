package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SettlementQueryJpaRepository
	extends Repository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {

	@Query("""
			select new com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate(
				s.status, sum(s.settlementTotalAmount), count(s))
			from Settlement s
			group by s.status
			""")
	List<SettlementStatusAggregate> aggregateByStatus();
}
