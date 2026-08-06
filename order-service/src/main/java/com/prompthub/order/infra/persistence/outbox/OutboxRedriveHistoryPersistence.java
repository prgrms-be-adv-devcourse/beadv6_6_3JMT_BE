package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.model.OutboxRedriveHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRedriveHistoryPersistence extends JpaRepository<OutboxRedriveHistory, UUID> {
}
