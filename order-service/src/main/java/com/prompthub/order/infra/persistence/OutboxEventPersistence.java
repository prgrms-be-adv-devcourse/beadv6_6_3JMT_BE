package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxEventPersistence extends JpaRepository<OutboxEvent, UUID> {
}
