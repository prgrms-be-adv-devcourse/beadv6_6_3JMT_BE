package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.Settlement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementJpaRepository extends JpaRepository<Settlement, UUID> {

	Optional<Settlement> findBySettlementId(UUID settlementId);
}
