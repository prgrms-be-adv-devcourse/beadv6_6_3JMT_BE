package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

public interface SettlementQueryJpaRepository
	extends Repository<Settlement, UUID> {

	@Query("""
			select new com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate(
				s.status, sum(s.settlementTotalAmount), count(s))
			from Settlement s
			group by s.status
			""")
	List<SettlementStatusAggregate> aggregateByStatus();

	@Query("""
			select new com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate(
				s.status, sum(s.settlementTotalAmount), count(s))
			from Settlement s
			where s.periodStart >= :periodStart
			  and s.periodStart < :periodEnd
			group by s.status
			""")
	List<SettlementStatusAggregate> aggregateByStatusBetween(
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd);

	@Query("""
			select s from Settlement s
			where s.sellerId = :sellerId
			  and s.periodStart >= :periodStart
			  and s.periodStart < :periodEnd
			order by s.periodStart asc, s.sellerSettlementId asc
			""")
	List<Settlement> findWeeklySettlements(
		@Param("sellerId") UUID sellerId,
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd);
}
