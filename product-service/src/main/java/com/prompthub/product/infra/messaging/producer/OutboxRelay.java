package com.prompthub.product.infra.messaging.producer;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "prompthub.outbox-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> stringKafkaTemplate;
	private final OutboxRelayProperties properties;

	@Transactional
	@Scheduled(fixedDelayString = "${prompthub.outbox-relay.fixed-delay-ms:5000}")
	public void publishPendingEvents() {
		outboxEventRepository.findPendingEvents(properties.batchSize()).forEach(this::publish);
	}

	private void publish(OutboxEvent event) {
		try {
			stringKafkaTemplate.send(properties.topic(), event.getAggregateId().toString(), event.getPayload()).get();
			event.markPublished(LocalDateTime.now());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			recordFailure(event, exception);
		} catch (ExecutionException exception) {
			recordFailure(event, exception);
		}
	}

	private void recordFailure(OutboxEvent event, Exception exception) {
		event.recordPublishFailure(properties.maxRetryCount());
		log.warn(
			"아웃박스 이벤트 발행에 실패했습니다. outboxEventId={}, eventType={}, retryCount={}, status={}",
			event.getEventId(), event.getEventType(), event.getRetryCount(), event.getStatus(), exception
		);
	}
}
