package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

	@Test
	@DisplayName("Outbox 이벤트 테이블은 Relay 조회용 status, occurred_at 복합 인덱스를 가진다")
	void outboxEventTable_hasStatusOccurredAtIndex() {
		Table table = OutboxEvent.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		assertThat(Arrays.stream(table.indexes()))
			.anySatisfy(index -> {
				assertThat(index.name()).isEqualTo("idx_outbox_event_status_occurred_at");
				assertThat(index.columnList()).isEqualTo("status, occurred_at");
			});
	}

	@Test
	@DisplayName("ORDER_PAID Outbox 이벤트는 주문 이벤트 토픽과 PENDING 상태로 생성된다")
	void orderPaid_createsPendingOutboxEvent() {
		String payload = "{\"orderId\":\"%s\"}".formatted(ORDER_ID);

		OutboxEvent outboxEvent = OutboxEvent.orderPaid(ORDER_ID, payload, APPROVED_AT);

		assertThat(outboxEvent.getId()).isInstanceOf(UUID.class);
		assertThat(outboxEvent.getAggregateId()).isEqualTo(ORDER_ID);
		assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");
		assertThat(outboxEvent.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(outboxEvent.getTopic()).isEqualTo("order-events");
		assertThat(outboxEvent.getPayload()).isEqualTo(payload);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getRetryCount()).isZero();
		assertThat(outboxEvent.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 성공 시 PUBLISHED 상태와 발행 시각을 기록한다")
	void markPublished_changesStatusAndPublishedAt() {
		OutboxEvent outboxEvent = OutboxEvent.orderPaid(
			ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);
		LocalDateTime publishedAt = APPROVED_AT.plusSeconds(10);

		outboxEvent.markPublished(publishedAt);

		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(outboxEvent.getPublishedAt()).isEqualTo(publishedAt);
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 실패 시 retry_count를 증가시키고 최대 횟수 미만이면 PENDING을 유지한다")
	void recordPublishFailure_incrementsRetryCountBeforeMaxRetryCount() {
		OutboxEvent outboxEvent = OutboxEvent.orderPaid(
			ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);

		outboxEvent.recordPublishFailure(3);

		assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}

	@Test
	@DisplayName("Outbox 이벤트 발행 실패 횟수가 최대 재시도 횟수에 도달하면 FAILED 상태로 변경한다")
	void recordPublishFailure_marksFailedWhenMaxRetryCountReached() {
		OutboxEvent outboxEvent = OutboxEvent.orderPaid(
			ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\"}",
			APPROVED_AT
		);

		outboxEvent.recordPublishFailure(3);
		outboxEvent.recordPublishFailure(3);
		outboxEvent.recordPublishFailure(3);

		assertThat(outboxEvent.getRetryCount()).isEqualTo(3);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}
}
