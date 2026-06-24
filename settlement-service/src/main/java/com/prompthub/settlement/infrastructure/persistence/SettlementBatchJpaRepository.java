package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementBatch;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementBatchJpaRepository extends JpaRepository<SettlementBatch, UUID> {
}
