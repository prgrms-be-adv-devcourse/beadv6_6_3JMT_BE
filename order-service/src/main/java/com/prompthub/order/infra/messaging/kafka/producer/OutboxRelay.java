package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
	prefix = "prompthub.outbox-relay",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class OutboxRelay {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final OutboxRelayProperties properties;

	@Transactional
	@Scheduled(fixedDelayString = "${prompthub.outbox-relay.fixed-delay-ms:5000}")
	public void publishPendingEvents() {
		outboxEventRepository.findPendingEvents(properties.batchSize())
			.forEach(this::publish);
	}

	private void publish(OutboxEvent event) {
		try {
			JsonNode payload = objectMapper.readTree(event.getPayload());
			kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), payload).get();
			event.markPublished(LocalDateTime.now());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			recordFailure(event, exception);
		} catch (ExecutionException | JacksonException exception) {
			recordFailure(event, exception);
		}
	}

	private void recordFailure(OutboxEvent event, Exception exception) {
		event.recordPublishFailure(properties.maxRetryCount());
		log.warn(
			"Failed to publish outbox event. outboxEventId={}, eventType={}, retryCount={}, status={}",
			event.getId(),
			event.getEventType(),
			event.getRetryCount(),
			event.getStatus(),
			exception
		);
	}
}
