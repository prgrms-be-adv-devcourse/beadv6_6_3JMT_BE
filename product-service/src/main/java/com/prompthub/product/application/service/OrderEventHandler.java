package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * order-events 처리의 멱등성(eventId+consumerGroup)과 판매수 변경을 한 트랜잭션으로 묶는다.
 * (kafka-event.md §7) — salesCount 증감은 자연 멱등이 아니라 재전송 시 중복되므로 eventId 기준으로 막는다.
 */
@Service
@RequiredArgsConstructor
public class OrderEventHandler {

	private static final String CONSUMER_GROUP = "product-service";

	private final ProductSalesCountService productSalesCountService;
	private final ProcessedEventRepository processedEventRepository;

	@Transactional
	public void handlePaid(UUID eventId, LocalDateTime occurredAt, List<UUID> productIds) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		productSalesCountService.incrementSalesCount(productIds);
		markProcessed(eventId, "ORDER_PAID", occurredAt);
	}

	@Transactional
	public void handleRefund(UUID eventId, LocalDateTime occurredAt, List<UUID> productIds) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		productSalesCountService.decrementSalesCount(productIds);
		markProcessed(eventId, "ORDER_REFUND", occurredAt);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, String eventType, LocalDateTime occurredAt) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, eventType, occurredAt));
	}
}
