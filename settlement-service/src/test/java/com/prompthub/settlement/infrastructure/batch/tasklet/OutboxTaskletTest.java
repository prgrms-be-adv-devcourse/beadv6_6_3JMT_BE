package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.OutboxEventUseCase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxTaskletTest {

    private OutboxEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = mock(OutboxEventUseCase.class);
    }

    @Test
    @DisplayName("시작 retry Tasklet은 requestedAt 이전 PENDING flush를 위임한다")
    void retryPending_executesWithRequestedAt() throws Exception {
        // given
        long requestedAt = Instant.parse("2026-07-13T01:00:00Z").toEpochMilli();
        RetryPendingOutboxTasklet tasklet = new RetryPendingOutboxTasklet(useCase);
        ReflectionTestUtils.setField(tasklet, "requestedAtParam", requestedAt);

        // when
        RepeatStatus result = tasklet.execute(null, null);

        // then
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(requestedAt), ZoneId.systemDefault());
        then(useCase).should().flushPendingBefore(cutoff);
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("마지막 flush Tasklet은 ExecutionContext의 settlementBatchId를 UUID로 위임한다")
    void flushCurrentBatch_executesWithBatchId() throws Exception {
        // given
        UUID batchId = UUID.randomUUID();
        FlushCurrentBatchOutboxTasklet tasklet = new FlushCurrentBatchOutboxTasklet(useCase);
        ReflectionTestUtils.setField(tasklet, "settlementBatchIdParam", batchId.toString());

        // when
        RepeatStatus result = tasklet.execute(null, null);

        // then
        then(useCase).should().flushBatch(batchId);
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("redrive Tasklet은 Job Parameter의 eventId 한 건만 위임한다")
    void redrive_executesWithEventId() throws Exception {
        // given
        UUID eventId = UUID.randomUUID();
        RedriveOutboxTasklet tasklet = new RedriveOutboxTasklet(useCase);
        ReflectionTestUtils.setField(tasklet, "eventIdParam", eventId.toString());

        // when
        RepeatStatus result = tasklet.execute(null, null);

        // then
        then(useCase).should().redrive(eventId);
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("유효하지 않은 redrive eventId는 Job을 실패시킨다")
    void redrive_invalidEventId_throws() {
        // given
        RedriveOutboxTasklet tasklet = new RedriveOutboxTasklet(useCase);
        ReflectionTestUtils.setField(tasklet, "eventIdParam", "not-a-uuid");

        // when & then
        assertThatThrownBy(() -> tasklet.execute(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        then(useCase).shouldHaveNoInteractions();
    }
}
