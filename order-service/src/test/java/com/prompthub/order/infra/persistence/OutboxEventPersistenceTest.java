package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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

		OutboxEvent found = outboxEventPersistence.findById(saved.getId()).orElseThrow();

		assertThat(found.getAggregateId()).isEqualTo(ORDER_ID);
		assertThat(found.getAggregateType()).isEqualTo("ORDER");
		assertThat(found.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(found.getTopic()).isEqualTo("order-events");
		assertThat(found.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(found.getRetryCount()).isZero();
		assertThat(found.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(found.getPublishedAt()).isNull();
	}
}
