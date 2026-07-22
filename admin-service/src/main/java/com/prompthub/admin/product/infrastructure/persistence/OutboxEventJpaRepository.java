package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID> {
}
