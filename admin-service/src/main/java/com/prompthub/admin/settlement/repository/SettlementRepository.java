package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.Settlement;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementRepository {

	private final SettlementJpaRepository jpaRepository;

	public Optional<Settlement> findBySettlementId(UUID settlementId) {
		return jpaRepository.findBySettlementId(settlementId);
	}

	public Settlement save(Settlement settlement) {
		return jpaRepository.save(settlement);
	}
}
