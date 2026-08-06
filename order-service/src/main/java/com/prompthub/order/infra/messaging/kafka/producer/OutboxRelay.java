package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.PublishOutcome;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RetryOutcome;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Slf4j
@Component
@ConditionalOnProperty(
	prefix = "prompthub.outbox-relay",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class OutboxRelay {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final OutboxRelayProperties properties;
	private final OutboxRetryProperties retryProperties;
	private final Clock clock;
	private final OutboxMetrics outboxMetrics;
	private final Supplier<String> leaseOwnerSupplier;

	@Autowired
	public OutboxRelay(
		OutboxEventRepository outboxEventRepository,
		KafkaTemplate<String, String> kafkaTemplate,
		OutboxRelayProperties properties,
		OutboxRetryProperties retryProperties,
		Clock clock,
		OutboxMetrics outboxMetrics
	) {
		this(
			outboxEventRepository,
			kafkaTemplate,
			properties,
			retryProperties,
			clock,
			outboxMetrics,
			() -> "outbox-relay-" + UUID.randomUUID()
		);
	}

	OutboxRelay(
		OutboxEventRepository outboxEventRepository,
		KafkaTemplate<String, String> kafkaTemplate,
		OutboxRelayProperties properties,
		OutboxRetryProperties retryProperties,
		Clock clock,
		OutboxMetrics outboxMetrics,
		Supplier<String> leaseOwnerSupplier
	) {
		this.outboxEventRepository = outboxEventRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.retryProperties = retryProperties;
		this.clock = clock;
		this.outboxMetrics = outboxMetrics;
		this.leaseOwnerSupplier = leaseOwnerSupplier;
	}

	@Scheduled(fixedDelayString = "${prompthub.outbox-relay.fixed-delay-ms:5000}")
	public void publishPendingEvents() {
		for (int index = 0; index < properties.batchSize(); index++) {
			String leaseOwner;
			Optional<OutboxEvent> claimed;
			try {
				leaseOwner = leaseOwnerSupplier.get();
				LocalDateTime claimedAt = LocalDateTime.now(clock);
				claimed = outboxEventRepository.claimNextPublishable(
					claimedAt,
					leaseOwner,
					claimedAt.plus(Duration.ofMillis(properties.leaseDurationMs()))
				);
			} catch (RuntimeException exception) {
				log.warn(
					"아웃박스 이벤트 claim 처리 중 예외가 발생했습니다. error={}",
					OutboxErrorSanitizer.sanitize(exception)
				);
				return;
			}

			if (claimed.isEmpty() || !publish(claimed.get(), leaseOwner)) {
				return;
			}
		}
	}

	private boolean publish(OutboxEvent event, String leaseOwner) {
		boolean retryAttempt = event.isRetryAttempt();

		try {
			Future<?> publishFuture = kafkaTemplate.send(
				properties.topic(), event.getAggregateId().toString(), event.getPayload()
			);
			publishFuture.get(properties.publishTimeoutMs(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			recordFailure(event, leaseOwner, LocalDateTime.now(clock), retryAttempt, exception);
			Thread.currentThread().interrupt();
			return false;
		} catch (TimeoutException | ExecutionException | RuntimeException exception) {
			recordFailure(event, leaseOwner, LocalDateTime.now(clock), retryAttempt, exception);
			return false;
		}

		recordPublishQuietly(PublishOutcome.SUCCESS);
		if (retryAttempt) {
			recordRetryQuietly(RetryOutcome.SUCCESS);
		}

		try {
			LocalDateTime publishedAt = LocalDateTime.now(clock);
			boolean markedPublished = outboxEventRepository.markPublished(
				event.getEventId(), leaseOwner, publishedAt
			);
			if (!markedPublished) {
				log.warn(
					"아웃박스 이벤트 완료 처리가 stale claim으로 무시되었습니다. outboxEventId={}, eventType={}, retryAttempt={}",
					event.getEventId(), event.getEventType(), retryAttempt
				);
			}
			return markedPublished;
		} catch (RuntimeException exception) {
			log.warn(
				"아웃박스 이벤트 완료 처리 중 예외가 발생했습니다. outboxEventId={}, eventType={}, error={}",
				event.getEventId(), event.getEventType(), OutboxErrorSanitizer.sanitize(exception)
			);
			return false;
		}
	}

	private void recordFailure(
		OutboxEvent event,
		String leaseOwner,
		LocalDateTime attemptedAt,
		boolean retryAttempt,
		Exception exception
	) {
		recordPublishQuietly(PublishOutcome.FAILURE);
		if (retryAttempt) {
			recordRetryQuietly(RetryOutcome.FAILURE);
		}
		String error = OutboxErrorSanitizer.sanitize(exception);
		Optional<OutboxEventStatus> status;
		try {
			status = outboxEventRepository.recordPublishFailure(
				event.getEventId(), leaseOwner, attemptedAt, error, retryProperties.toPolicy()
			);
		} catch (RuntimeException persistenceException) {
			log.warn(
				"아웃박스 이벤트 실패 처리 중 예외가 발생했습니다. outboxEventId={}, eventType={}, error={}",
				event.getEventId(), event.getEventType(), OutboxErrorSanitizer.sanitize(persistenceException)
			);
			return;
		}
		if (status.isEmpty()) {
			log.warn(
				"아웃박스 이벤트 실패 처리가 stale claim으로 무시되었습니다. outboxEventId={}, eventType={}, retryAttempt={}, error={}",
				event.getEventId(), event.getEventType(), retryAttempt, error
			);
			return;
		}
		log.warn(
			"아웃박스 이벤트 발행에 실패했습니다. outboxEventId={}, eventType={}, retryAttempt={}, status={}, error={}",
			event.getEventId(), event.getEventType(), retryAttempt, status.get(), error
		);
	}

	private void recordPublishQuietly(PublishOutcome outcome) {
		try {
			outboxMetrics.recordPublish(outcome);
		} catch (RuntimeException ignored) {
			log.warn("Outbox publish metric recording failed. outcome={}", outcome);
		}
	}

	private void recordRetryQuietly(RetryOutcome outcome) {
		try {
			outboxMetrics.recordRetry(outcome);
		} catch (RuntimeException ignored) {
			log.warn("Outbox retry metric recording failed. outcome={}", outcome);
		}
	}
}
