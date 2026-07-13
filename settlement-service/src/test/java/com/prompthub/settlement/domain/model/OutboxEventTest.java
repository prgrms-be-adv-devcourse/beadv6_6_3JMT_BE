package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.OutboxEventInvalidStateException;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SETTLEMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 13, 10, 0);

    @Test
    @DisplayName("아웃박스 이벤트를 생성하면 PENDING 상태와 발행 정보가 저장된다")
    void create_initializesPendingEvent() {
        // when
        OutboxEvent event = pendingEvent();

        // then
        assertThat(event.getEventId()).isEqualTo(EVENT_ID);
        assertThat(event.getSettlementBatchId()).isEqualTo(BATCH_ID);
        assertThat(event.getAggregateType()).isEqualTo("SETTLEMENT");
        assertThat(event.getAggregateId()).isEqualTo(SETTLEMENT_ID);
        assertThat(event.getEventType()).isEqualTo("SETTLEMENT_CREATED");
        assertThat(event.getTopic()).isEqualTo("settlement-events");
        assertThat(event.getPayload()).isEqualTo("{\"eventId\":\"" + EVENT_ID + "\"}");
        assertThat(event.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.isPending()).isTrue();
    }

    @Test
    @DisplayName("아웃박스 테이블에는 상태·배치·aggregate 조회 인덱스가 선언된다")
    void table_declaresRelayIndexes() {
        // given
        Table table = OutboxEvent.class.getAnnotation(Table.class);

        // when
        Set<String> indexNames = Arrays.stream(table.indexes())
                .map(Index::name)
                .collect(Collectors.toSet());

        // then
        assertThat(indexNames).containsExactlyInAnyOrder(
                "idx_settlement_outbox_status_attempted_occurred",
                "idx_settlement_outbox_batch_status_occurred",
                "idx_settlement_outbox_aggregate_id");
    }

    @Test
    @DisplayName("PENDING 이벤트 발행에 성공하면 PUBLISHED 상태와 발행 시각이 기록된다")
    void markPublished_fromPending_recordsPublishedAt() {
        // given
        OutboxEvent event = pendingEvent();
        LocalDateTime publishedAt = OCCURRED_AT.plusMinutes(1);

        // when
        event.markPublished(publishedAt);

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(event.getLastAttemptedAt()).isEqualTo(publishedAt);
        assertThat(event.isPending()).isFalse();
    }

    @Test
    @DisplayName("1회와 2회 발행 실패는 PENDING으로 유지하고 실패 정보를 누적한다")
    void recordPublishFailure_beforeLimit_remainsPending() {
        // given
        OutboxEvent event = pendingEvent();

        // when
        event.recordPublishFailure("broker down", OCCURRED_AT.plusMinutes(1), 3);
        event.recordPublishFailure("broker still down", OCCURRED_AT.plusMinutes(2), 3);

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getLastFailureReason()).isEqualTo("broker still down");
        assertThat(event.getLastAttemptedAt()).isEqualTo(OCCURRED_AT.plusMinutes(2));
        assertThat(event.getFailedAt()).isNull();
    }

    @Test
    @DisplayName("3회 발행 실패하면 FAILED 상태와 실패 시각이 기록된다")
    void recordPublishFailure_reachesLimit_becomesFailed() {
        // given
        OutboxEvent event = pendingEvent();

        // when
        event.recordPublishFailure("first", OCCURRED_AT.plusMinutes(1), 3);
        event.recordPublishFailure("second", OCCURRED_AT.plusMinutes(2), 3);
        event.recordPublishFailure("third", OCCURRED_AT.plusMinutes(3), 3);

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getFailedAt()).isEqualTo(OCCURRED_AT.plusMinutes(3));
        assertThat(event.isPending()).isFalse();
    }

    @Test
    @DisplayName("발행 실패 사유는 컬럼 최대 길이인 1000자로 제한한다")
    void recordPublishFailure_longReason_truncatesReason() {
        // given
        OutboxEvent event = pendingEvent();

        // when
        event.recordPublishFailure("x".repeat(1_001), OCCURRED_AT.plusMinutes(1), 3);

        // then
        assertThat(event.getLastFailureReason()).hasSize(1_000);
    }

    @Test
    @DisplayName("FAILED 이벤트를 재처리 대기열로 되돌리면 실패 정보가 초기화된다")
    void requeueForRedrive_fromFailed_resetsFailureState() {
        // given
        OutboxEvent event = pendingEvent();
        event.recordPublishFailure("first", OCCURRED_AT.plusMinutes(1), 1);

        // when
        event.requeueForRedrive();

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getLastAttemptedAt()).isNull();
        assertThat(event.getLastFailureReason()).isNull();
        assertThat(event.getFailedAt()).isNull();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("FAILED 상태가 아닌 이벤트는 재처리 대기열로 되돌릴 수 없다")
    void requeueForRedrive_notFailed_throwsException() {
        // given
        OutboxEvent event = pendingEvent();

        // when & then
        assertThatThrownBy(event::requeueForRedrive)
                .isInstanceOf(OutboxEventInvalidStateException.class);
    }

    @Test
    @DisplayName("PENDING 상태가 아닌 이벤트는 발행 결과를 다시 기록할 수 없다")
    void recordResult_notPending_throwsException() {
        // given
        OutboxEvent event = pendingEvent();
        event.markPublished(OCCURRED_AT.plusMinutes(1));

        // when & then
        assertThatThrownBy(() -> event.recordPublishFailure("late failure", OCCURRED_AT.plusMinutes(2), 3))
                .isInstanceOf(OutboxEventInvalidStateException.class);
        assertThatThrownBy(() -> event.markPublished(OCCURRED_AT.plusMinutes(2)))
                .isInstanceOf(OutboxEventInvalidStateException.class);
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.create(
                EVENT_ID,
                BATCH_ID,
                "SETTLEMENT",
                SETTLEMENT_ID,
                "SETTLEMENT_CREATED",
                "settlement-events",
                "{\"eventId\":\"" + EVENT_ID + "\"}",
                OCCURRED_AT);
    }
}
