package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import java.util.UUID;

public interface ProcessedEventRepository {

	boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);

	void save(ProductProcessedEvent processedEvent);
}
