package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.exception.OutboxEventInvalidStateException;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.EVENT_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

	private static final OutboxRetryPolicy POLICY = new OutboxRetryPolicy(
		Duration.ofSeconds(5), Duration.ofMinutes(5), 10
	);

	@Test
	@DisplayName("Outbox 이벤트 테이블은 Relay 조회용 status, occurred_at 복합 인덱스를 가진다")
	void outboxEventTable_hasStatusOccurredAtIndex() {
		Table table = OutboxEvent.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		assertThat(Arrays.stream(table.indexes()))
			.anySatisfy(index -> {
				assertThat(index.name()).isEqualTo("idx_order_outbox_event_status_occurred_at");
				assertThat(index.columnList()).isEqualTo("status, occurred_at");
			});
	}

	@Test
	@DisplayName("Outbox 이벤트 테이블은 Aggregate 조회용 aggregate_id 인덱스를 가진다")
	void outboxEventTable_hasAggregateIdIndex() {
		Table table = OutboxEvent.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		assertThat(Arrays.stream(table.indexes()))
			.anySatisfy(index -> {
				assertThat(index.name()).isEqualTo("idx_order_outbox_event_aggregate_id");
				assertThat(index.columnList()).isEqualTo("aggregate_id");
			});
	}

	@Test
	@DisplayName("Outbox 이벤트 테이블은 발행 가능 이벤트 조회용 전체 복합 인덱스를 가진다")
	void outboxEventTable_hasPublishableIndex() {
		Table table = OutboxEvent.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		assertThat(Arrays.stream(table.indexes()))
			.anySatisfy(index -> {
				assertThat(index.name()).isEqualTo("idx_order_outbox_event_publishable");
				assertThat(index.columnList()).isEqualTo("status, next_attempt_at, lease_until, occurred_at");
			});
	}

	@Test
	@DisplayName("Outbox 이벤트는 전달받은 이벤트 정보와 PENDING 상태로 생성된다")
	void create_createsPendingOutboxEvent() {
		String payload = "{\"orderId\":\"%s\"}".formatted(ORDER_ID);

		OutboxEvent outboxEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			payload,
			APPROVED_AT
		);

		assertThat(outboxEvent.getEventId()).isEqualTo(EVENT_ID);
		assertThat(outboxEvent.getAggregateId()).isEqualTo(ORDER_ID);
		assertThat(outboxEvent.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(outboxEvent.getPayload()).isEqualTo(payload);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getRetryCount()).isZero();
		assertThat(outboxEvent.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(outboxEvent.getPublishedAt()).isNull();
		assertThat(outboxEvent.getNextAttemptAt()).isEqualTo(APPROVED_AT);
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 성공 시 PUBLISHED 상태와 발행 시각을 기록한다")
	void markPublished_changesStatusAndPublishedAt() {
		OutboxEvent outboxEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);
		LocalDateTime publishedAt = APPROVED_AT.plusSeconds(10);

		outboxEvent.markPublished(publishedAt);

		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(outboxEvent.getPublishedAt()).isEqualTo(publishedAt);
		assertThat(outboxEvent.getNextAttemptAt()).isNull();
		assertThat(outboxEvent.getLeaseOwner()).isNull();
		assertThat(outboxEvent.getLeaseUntil()).isNull();
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 실패 시 재시도 시각을 예약하고 임대를 해제한다")
	void recordPublishFailure_schedulesRetryAndClearsLease() {
		OutboxEvent outboxEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);

		outboxEvent.claim("relay-a", APPROVED_AT.plusSeconds(30));
		outboxEvent.recordPublishFailure(
			APPROVED_AT.plusSeconds(1),
			"TimeoutException: Kafka publish timed out",
			POLICY
		);

		assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getNextAttemptAt()).isEqualTo(APPROVED_AT.plusSeconds(6));
		assertThat(outboxEvent.getLeaseOwner()).isNull();
		assertThat(outboxEvent.getLeaseUntil()).isNull();
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 실패 횟수가 최대 재시도 횟수에 도달하면 FAILED 상태로 변경한다")
	void recordPublishFailure_marksFailedWhenMaxRetryCountReached() {
		OutboxEvent outboxEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);

		for (int failureCount = 1; failureCount <= 10; failureCount++) {
			outboxEvent.recordPublishFailure(
				APPROVED_AT.plusSeconds(failureCount),
				"Kafka unavailable",
				POLICY
			);
		}

		assertThat(outboxEvent.getRetryCount()).isEqualTo(10);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(outboxEvent.getNextAttemptAt()).isNull();
	}

	@Test
	@DisplayName("FAILED Outbox 이벤트는 수동 재처리 시 PENDING으로 전환하고 이전 오류를 보존한다")
	void redrive_resetsRetryStateAndPreservesError() {
		OutboxEvent failedEvent = OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);
		for (int failureCount = 1; failureCount <= 10; failureCount++) {
			failedEvent.recordPublishFailure(
				APPROVED_AT.plusSeconds(failureCount),
				"Kafka unavailable",
				POLICY
			);
		}

		String previousError = failedEvent.getLastError();
		failedEvent.redrive(APPROVED_AT.plusHours(1));

		assertThat(failedEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(failedEvent.getRetryCount()).isZero();
		assertThat(failedEvent.getNextAttemptAt()).isEqualTo(APPROVED_AT.plusHours(1));
		assertThat(failedEvent.getLastError()).isEqualTo(previousError);
		assertThat(failedEvent.getLeaseOwner()).isNull();
		assertThat(failedEvent.getLeaseUntil()).isNull();
	}

	@Test
	@DisplayName("PENDING Outbox 이벤트는 redrive할 수 없다")
	void redrive_throwsWhenEventIsPending() {
		OutboxEvent outboxEvent = createPendingEvent();

		assertThatThrownBy(() -> outboxEvent.redrive(APPROVED_AT.plusHours(1)))
			.isInstanceOf(OutboxEventInvalidStateException.class);
	}

	@Test
	@DisplayName("PUBLISHED Outbox 이벤트는 claim, publish, 실패 기록을 할 수 없다")
	void transitions_throwWhenEventIsPublished() {
		OutboxEvent outboxEvent = createPendingEvent();
		outboxEvent.markPublished(APPROVED_AT.plusSeconds(1));

		assertInvalidPendingTransitions(outboxEvent);
	}

	@Test
	@DisplayName("FAILED Outbox 이벤트는 claim, publish, 실패 기록을 할 수 없다")
	void transitions_throwWhenEventIsFailed() {
		assertInvalidPendingTransitions(createFailedEvent());
	}

	private void assertInvalidPendingTransitions(OutboxEvent outboxEvent) {
		assertThatThrownBy(() -> outboxEvent.claim("relay-a", APPROVED_AT.plusSeconds(30)))
			.isInstanceOf(OutboxEventInvalidStateException.class);
		assertThatThrownBy(() -> outboxEvent.markPublished(APPROVED_AT.plusSeconds(1)))
			.isInstanceOf(OutboxEventInvalidStateException.class);
		assertThatThrownBy(() -> outboxEvent.recordPublishFailure(
			APPROVED_AT.plusSeconds(1), "Kafka unavailable", POLICY
		)).isInstanceOf(OutboxEventInvalidStateException.class);
	}

	private OutboxEvent createPendingEvent() {
		return OutboxEvent.create(
			EVENT_ID,
			ORDER_ID,
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);
	}

	private OutboxEvent createFailedEvent() {
		OutboxEvent outboxEvent = createPendingEvent();
		for (int failureCount = 1; failureCount <= 10; failureCount++) {
			outboxEvent.recordPublishFailure(
				APPROVED_AT.plusSeconds(failureCount), "Kafka unavailable", POLICY
			);
		}
		return outboxEvent;
	}
}
