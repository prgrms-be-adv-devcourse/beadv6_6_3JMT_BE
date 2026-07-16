package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OutboxEventPersistenceTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Test
	@DisplayName("OutboxEvent를 outbox_event 테이블에 저장하고 조회한다")
	void saveAndFindOutboxEvent() {
		OutboxEvent outboxEvent = OutboxEvent.orderPaid(
			ORDER_ID,
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
	@DisplayName("PENDING OutboxEvent를 발생 시각 오름차순으로 지정한 개수만큼 조회한다")
	void findPendingEventsOrderByOccurredAtAsc() {
		OutboxEvent newestPending = OutboxEvent.orderPaid(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"newest\"}",
			APPROVED_AT.plusMinutes(2)
		);
		OutboxEvent oldestPending = OutboxEvent.orderPaid(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"oldest\"}",
			APPROVED_AT
		);
		OutboxEvent published = OutboxEvent.orderPaid(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"published\"}",
			APPROVED_AT.minusMinutes(1)
		);
		published.markPublished(APPROVED_AT.plusMinutes(3));
		OutboxEvent middlePending = OutboxEvent.orderPaid(
			UUID.randomUUID(),
			"{\"eventType\":\"ORDER_PAID\",\"order\":\"middle\"}",
			APPROVED_AT.plusMinutes(1)
		);

		outboxEventPersistence.saveAll(List.of(newestPending, oldestPending, published, middlePending));
		entityManager.flush();
		entityManager.clear();

		List<OutboxEvent> found = outboxEventPersistence.findByStatusOrderByOccurredAtAsc(
			OutboxEventStatus.PENDING,
			PageRequest.of(0, 2)
		);

		assertThat(found)
			.extracting(OutboxEvent::getAggregateId)
			.containsExactly(oldestPending.getAggregateId(), middlePending.getAggregateId());
	}
}
