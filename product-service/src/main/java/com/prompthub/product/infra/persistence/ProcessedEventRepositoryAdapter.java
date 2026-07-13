package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

	private final ProductProcessedEventJpaRepository processedEventJpaRepository;

	@Override
	public boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup) {
		return processedEventJpaRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
	}

	@Override
	public void save(ProductProcessedEvent processedEvent) {
		processedEventJpaRepository.save(processedEvent);
	}
}
