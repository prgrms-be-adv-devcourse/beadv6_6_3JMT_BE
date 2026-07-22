package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;
import com.prompthub.admin.product.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter implements OutboxEventRepository {

	private final OutboxEventJpaRepository outboxEventJpaRepository;

	@Override
	public void append(OutboxEvent outboxEvent) {
		outboxEventJpaRepository.save(outboxEvent);
	}
}
