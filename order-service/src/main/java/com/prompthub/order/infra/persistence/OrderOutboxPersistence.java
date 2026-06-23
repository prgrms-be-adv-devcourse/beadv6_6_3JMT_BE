package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.OrderOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderOutboxPersistence extends JpaRepository<OrderOutbox, UUID> {
}
