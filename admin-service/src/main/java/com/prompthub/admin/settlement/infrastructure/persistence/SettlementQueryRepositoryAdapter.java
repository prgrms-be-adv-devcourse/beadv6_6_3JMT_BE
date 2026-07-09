package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;

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
		Page<Settlement> result = jpaRepository.findAll(statusSpec(status), pageable);
		return new SettlementPage(result.getContent(), result.getTotalElements());
	}

	@Override
	public List<SettlementStatusAggregate> aggregateByStatus() {
		return jpaRepository.aggregateByStatus();
	}

	private static Specification<Settlement> statusSpec(SettlementDisplayStatus status) {
		if (status == null) {
			return null;
		}
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}
}
