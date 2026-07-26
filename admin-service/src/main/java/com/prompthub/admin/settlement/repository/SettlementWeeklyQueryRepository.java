package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.entity.Settlement;
import com.prompthub.admin.settlement.repository.SettlementWeeklyStatusCount;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementWeeklyQueryRepository {

	private final SettlementQueryJpaRepository jpaRepository;

	public WeeklyPage findWeeklyPage(
			SettlementDisplayStatus status,
			YearMonth settlementMonth,
			int page,
			int size) {
		LocalDate periodStart = periodStart(settlementMonth);
		LocalDate periodEnd = periodEnd(settlementMonth);
		Page<Settlement> result =
				jpaRepository.findWeeklyPage(status, periodStart, periodEnd, PageRequest.of(page, size));
		return new WeeklyPage(result.getContent(), result.getTotalElements());
	}

	public List<SettlementWeeklyStatusCount> findStatusCounts(YearMonth settlementMonth) {
		return jpaRepository.countWeeklyStatuses(
				periodStart(settlementMonth), periodEnd(settlementMonth));
	}

	private LocalDate periodStart(YearMonth settlementMonth) {
		return settlementMonth == null ? null : settlementMonth.atDay(1).minusDays(3);
	}

	private LocalDate periodEnd(YearMonth settlementMonth) {
		return settlementMonth == null ? null
				: settlementMonth.plusMonths(1).atDay(1).minusDays(3);
	}

	public record WeeklyPage(List<Settlement> content, long totalElements) {
	}
}
