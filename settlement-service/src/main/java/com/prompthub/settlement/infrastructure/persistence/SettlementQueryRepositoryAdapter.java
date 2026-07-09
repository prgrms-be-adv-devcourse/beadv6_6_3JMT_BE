package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import jakarta.persistence.criteria.Predicate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementQueryRepositoryAdapter implements SettlementQueryRepository {

	private final SettlementQueryJpaRepository jpaRepository;

	@Override
	public SettlementPage findPage(SettlementDisplayStatus status, int page, int size) {
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "calculatedAt"));
		Page<Settlement> result = jpaRepository.findAll(displayStatusSpec(status), pageable);
		return new SettlementPage(result.getContent(), result.getTotalElements());
	}

	@Override
	public SettlementPage findPageBySeller(
		UUID sellerId,
		SettlementDisplayStatus status,
		YearMonth period,
		int page,
		int size
	) {
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodStart"));
		Specification<Settlement> spec = sellerSpec(sellerId);
		Specification<Settlement> statusSpec = displayStatusSpec(status);
		if (statusSpec != null) {
			spec = spec.and(statusSpec);
		}
		Specification<Settlement> periodSpec = periodSpec(period);
		if (periodSpec != null) {
			spec = spec.and(periodSpec);
		}
		Page<Settlement> result = jpaRepository.findAll(spec, pageable);
		return new SettlementPage(result.getContent(), result.getTotalElements());
	}

	@Override
	public List<SettlementStatusAggregate> aggregateByStatus() {
		return jpaRepository.aggregateByStatus();
	}

	private static Specification<Settlement> sellerSpec(UUID sellerId) {
		return (root, query, cb) -> cb.equal(root.get("sellerId"), sellerId);
	}

	private static Specification<Settlement> periodSpec(YearMonth period) {
		if (period == null) {
			return null;
		}
		LocalDate start = period.atDay(1);
		LocalDate end = period.atEndOfMonth();
		return (root, query, cb) -> cb.between(root.get("periodStart"), start, end);
	}

	private static Specification<Settlement> displayStatusSpec(SettlementDisplayStatus status) {
		if (status == null) {
			return null;
		}
		return (root, query, cb) -> {
			List<Predicate> combos = new ArrayList<>();
			for (SettlementStatus settlementStatus : SettlementStatus.values()) {
				for (PayoutStatus payoutStatus : PayoutStatus.values()) {
					if (SettlementDisplayStatus.from(settlementStatus, payoutStatus) == status) {
						combos.add(cb.and(
							cb.equal(root.get("settlementStatus"), settlementStatus),
							cb.equal(root.get("payoutStatus"), payoutStatus)));
					}
				}
			}
			return cb.or(combos.toArray(new Predicate[0]));
		};
	}
}
