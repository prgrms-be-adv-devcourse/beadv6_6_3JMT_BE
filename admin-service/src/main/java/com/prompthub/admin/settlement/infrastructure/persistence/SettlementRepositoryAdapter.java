package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.repository.SettlementRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementRepositoryAdapter implements SettlementRepository {

	private final SettlementJpaRepository jpaRepository;

	@Override
	public Optional<Settlement> findById(UUID id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Settlement save(Settlement settlement) {
		return jpaRepository.save(settlement);
	}
}
