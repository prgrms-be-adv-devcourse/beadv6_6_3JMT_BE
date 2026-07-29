package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.repository.SettlementStatusAggregate;

import java.time.LocalDate;
import java.time.YearMonth;
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
public class SettlementQueryRepository {

	private final SettlementQueryJpaRepository jpaRepository;

	public List<SettlementStatusAggregate> aggregateByStatus() {
		return jpaRepository.aggregateByStatus();
	}

	public List<SettlementStatusAggregate> aggregateByStatus(YearMonth settlementMonth) {
		if (settlementMonth == null) {
			return aggregateByStatus();
		}
		LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
		LocalDate periodEnd = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
		return jpaRepository.aggregateByStatusBetween(periodStart, periodEnd);
	}

	public DeliveryPage findDeliveryPage(SettlementDeliveryListQuery query) {
		Page<SettlementDelivery> page = jpaRepository.findDeliveryPage(
			query.status(),
			query.problemOnly(),
			query.identifier(),
			PageRequest.of(query.page(), query.size()));
		return new DeliveryPage(page.getContent(), page.getTotalElements());
	}

	public Map<SettlementDeliveryStatus, Long> countDeliveryByStatus() {
		EnumMap<SettlementDeliveryStatus, Long> counts =
			new EnumMap<>(SettlementDeliveryStatus.class);
		for (SettlementDeliveryStatus status : SettlementDeliveryStatus.values()) {
			counts.put(status, 0L);
		}
		for (SettlementDeliveryStatusCount count : jpaRepository.countDeliveryByStatus()) {
			counts.put(count.status(), count.count());
		}
		return counts;
	}

	public Optional<SettlementDelivery> findDeliveryById(
		UUID settlementDeliveryId) {
		return jpaRepository.findDeliveryById(settlementDeliveryId);
	}

	public record DeliveryPage(
		List<SettlementDelivery> content,
		long totalElements) {
	}
}
