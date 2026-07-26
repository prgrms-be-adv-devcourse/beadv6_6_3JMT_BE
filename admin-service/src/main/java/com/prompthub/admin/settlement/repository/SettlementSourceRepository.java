package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.SettlementSourceLine;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSourceRepository {

	private final SettlementSourceLineJpaRepository jpaRepository;

	public List<SettlementSourceLine> findBySettlementId(UUID settlementId) {
		return jpaRepository.findBySettlementId(settlementId);
	}
}
