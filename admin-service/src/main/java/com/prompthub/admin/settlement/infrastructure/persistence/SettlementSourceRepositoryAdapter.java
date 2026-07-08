package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import com.prompthub.admin.settlement.domain.repository.SettlementSourceRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSourceRepositoryAdapter implements SettlementSourceRepository {

	private final SettlementSourceLineJpaRepository jpaRepository;

	@Override
	public List<SettlementSourceLine> findBySettlementId(UUID settlementId) {
		return jpaRepository.findBySettlementId(settlementId);
	}
}
