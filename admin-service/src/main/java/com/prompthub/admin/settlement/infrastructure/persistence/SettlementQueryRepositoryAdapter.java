package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementQueryRepositoryAdapter implements SettlementQueryRepository {

	private final SettlementQueryJpaRepository jpaRepository;

	@Override
	public List<SettlementStatusAggregate> aggregateByStatus() {
		return jpaRepository.aggregateByStatus();
	}

	@Override
	public List<SettlementStatusAggregate> aggregateByStatus(YearMonth settlementMonth) {
		if (settlementMonth == null) {
			return aggregateByStatus();
		}
		LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
		LocalDate periodEnd = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
		return jpaRepository.aggregateByStatusBetween(periodStart, periodEnd);
	}
}
