package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;

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
