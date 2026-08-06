package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.outbox.OutboxEventAdapter;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.EVENT_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({OutboxEventAdapter.class, QuerydslConfig.class, TestJpaConfig.class})
class OutboxEventPersistenceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 30);
	private static final OutboxRetryPolicy RETRY_POLICY = new OutboxRetryPolicy(
		Duration.ofSeconds(1),
		Duration.ofMinutes(1),
		3
	);

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("OutboxEvent를 outbox_event 테이블에 저장하고 조회한다")
	void saveAndFindOutboxEvent() {
		OutboxEvent outboxEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);

		OutboxEvent saved = outboxEventPersistence.save(outboxEvent);
		entityManager.flush();
		entityManager.clear();

		OutboxEvent found = outboxEventPersistence.findById(saved.getEventId()).orElseThrow();

		assertThat(found.getAggregateId()).isEqualTo(ORDER_ID);
		assertThat(found.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(found.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(found.getRetryCount()).isZero();
		assertThat(found.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(found.getPublishedAt()).isNull();
	}

	@Test
	@DisplayName("발행 시각이 도래하고 lease가 없거나 만료된 PENDING 이벤트만 claim한다")
	void claimNextPublishableClaimsOnlyDueUnleasedOrExpiredLeasePendingEvent() {
		OutboxEvent dueEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"due\"}",
			NOW.minusMinutes(2)
		);
		OutboxEvent expiredLeaseEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"expired\"}",
			NOW.minusMinutes(1)
		);
		expiredLeaseEvent.claim("stale-relay", NOW.minusSeconds(1));
		OutboxEvent activeLeaseEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"active\"}",
			NOW.minusMinutes(3)
		);
		activeLeaseEvent.claim("active-relay", NOW.plusSeconds(30));
		OutboxEvent futureEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"future\"}",
			NOW.plusSeconds(1)
		);
		OutboxEvent failedEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"failed\"}",
			NOW.minusMinutes(4)
		);
		failedEvent.recordPublishFailure(NOW.minusMinutes(4), "failed", new OutboxRetryPolicy(
			Duration.ofSeconds(1), Duration.ofSeconds(1), 1
		));
		saveAndFlushInNewTransaction(
			dueEvent, expiredLeaseEvent, activeLeaseEvent, futureEvent, failedEvent
		);
		entityManager.clear();

		OutboxEvent claimed = outboxEventRepository.claimNextPublishable(
			NOW,
			"relay-a",
			NOW.plusSeconds(30)
		).orElseThrow();

		assertThat(claimed.getEventId()).isEqualTo(dueEvent.getEventId());
		assertThat(claimed.getLeaseOwner()).isEqualTo("relay-a");
		assertThat(claimed.getLeaseUntil()).isEqualTo(NOW.plusSeconds(30));
		assertThat(outboxEventRepository.claimNextPublishable(
			NOW,
			"relay-b",
			NOW.plusSeconds(30)
		)).get()
			.extracting(OutboxEvent::getEventId)
			.isEqualTo(expiredLeaseEvent.getEventId());
		assertThat(outboxEventRepository.markPublished(
			expiredLeaseEvent.getEventId(),
			"stale-relay",
			NOW
		)).isFalse();
		assertThat(outboxEventRepository.claimNextPublishable(
			NOW,
			"relay-c",
			NOW.plusSeconds(30)
		)).isEmpty();
	}

	@Test
	@DisplayName("lease owner가 아닌 relay는 성공이나 실패 결과를 기록할 수 없다")
	void staleOwnerCannotMutateClaimedEvent() {
		OutboxEvent event = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\"}",
			NOW
		);
		saveAndFlushInNewTransaction(event);

		outboxEventRepository.claimNextPublishable(NOW, "relay-a", NOW.plusSeconds(30));

		assertThat(outboxEventRepository.markPublished(event.getEventId(), "relay-b", NOW)).isFalse();
		assertThat(outboxEventRepository.recordPublishFailure(
			event.getEventId(), "relay-b", NOW, "broker unavailable", RETRY_POLICY
		)).isEmpty();

		entityManager.flush();
		entityManager.clear();
		OutboxEvent found = outboxEventPersistence.findById(event.getEventId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(found.getLeaseOwner()).isEqualTo("relay-a");
		assertThat(found.getRetryCount()).isZero();
	}

	@Test
	@DisplayName("미발행 이벤트 적체 지표와 내부 redrive용 잠금 조회를 제공한다")
	void providesUnpublishedSummaryAndLockedEventLookup() {
		long existingFailedCount = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
		LocalDateTime existingOldestUnpublished = outboxEventRepository.findOldestUnpublishedOccurredAt()
			.orElse(null);
		OutboxEvent failedEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"failed\"}",
			NOW.minusMinutes(2)
		);
		failedEvent.recordPublishFailure(NOW.minusMinutes(1), "failed", new OutboxRetryPolicy(
			Duration.ofSeconds(1), Duration.ofSeconds(1), 1
		));
		OutboxEvent newestFailedEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"newest-failed\"}",
			NOW.minusMinutes(1)
		);
		newestFailedEvent.recordPublishFailure(NOW, "failed", new OutboxRetryPolicy(
			Duration.ofSeconds(1), Duration.ofSeconds(1), 1
		));
		OutboxEvent pendingEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"pending\"}",
			NOW
		);
		OutboxEvent publishedEvent = createPendingEvent(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"published\"}",
			NOW.minusMinutes(3)
		);
		publishedEvent.markPublished(NOW.minusMinutes(2));
		outboxEventPersistence.saveAll(List.of(
			failedEvent, newestFailedEvent, pendingEvent, publishedEvent
		));
		entityManager.flush();
		entityManager.clear();

		assertThat(outboxEventRepository.findByIdForUpdate(failedEvent.getEventId()))
			.isPresent();
		assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED))
			.isEqualTo(existingFailedCount + 2);
		assertThat(outboxEventRepository.findOldestUnpublishedOccurredAt()).contains(
			oldest(existingOldestUnpublished, failedEvent.getOccurredAt())
		);
	}

	private LocalDateTime oldest(LocalDateTime first, LocalDateTime second) {
		if (first == null || second.isBefore(first)) {
			return second;
		}
		return first;
	}

	private OutboxEvent createPendingEvent(
		UUID aggregateId,
		String payload,
		LocalDateTime occurredAt
	) {
		return OutboxEvent.create(
			UUID.randomUUID(),
			aggregateId,
			"ORDER_PAID",
			payload,
			occurredAt
		);
	}

	private void saveAndFlushInNewTransaction(OutboxEvent... events) {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transactionTemplate.executeWithoutResult(status -> {
			outboxEventPersistence.saveAll(List.of(events));
			outboxEventPersistence.flush();
		});
	}
}
