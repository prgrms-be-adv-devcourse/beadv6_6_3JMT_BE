package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductProcessedEventJpaRepository extends JpaRepository<ProductProcessedEvent, Long> {

	boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
