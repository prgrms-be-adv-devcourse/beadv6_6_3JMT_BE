package com.prompthub.settlement.infrastructure.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementBatchFailureListenerTest {

    private SettlementBatchRepository repository;
    private SettlementBatchFailureListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementBatchRepository.class);
        listener = new SettlementBatchFailureListener(repository);
    }

    @Test
    @DisplayName("PROCESSING 배치의 Job이 실패하면 배치를 FAILED로 저장한다")
    void afterJob_failedProcessingBatch_marksFailed() {
        // given
        SettlementBatch batch = batch();
        JobExecution execution = execution(BatchStatus.FAILED, batch.getId());
        execution.addFailureException(new IllegalStateException("DB 연결 실패"));
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        // when
        listener.afterJob(execution);

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("DB 연결 실패");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("완료 후 outbox flush가 실패해도 COMPLETED 배치 상태는 덮어쓰지 않는다")
    void afterJob_flushFailsAfterCompletion_keepsCompletedBatch() {
        // given
        SettlementBatch batch = batch();
        batch.complete();
        JobExecution execution = execution(BatchStatus.FAILED, batch.getId());
        execution.addFailureException(new IllegalStateException("outbox DB failure"));
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        // when & then
        assertThatCode(() -> listener.afterJob(execution)).doesNotThrowAnyException();
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        then(repository).should(never()).save(batch);
    }

    @Test
    @DisplayName("정상 완료한 Job은 배치 실패 처리를 조회하지 않는다")
    void afterJob_completedJob_doesNothing() {
        // given
        SettlementBatch batch = batch();
        JobExecution execution = execution(BatchStatus.COMPLETED, batch.getId());

        // when
        listener.afterJob(execution);

        // then
        then(repository).shouldHaveNoInteractions();
    }

    private SettlementBatch batch() {
        SettlementBatch batch = SettlementBatch.start(
                "SETTLE-202606-SCHEDULED-1",
                1L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                TriggerType.SCHEDULED);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        return batch;
    }

    private JobExecution execution(BatchStatus status, UUID batchId) {
        JobExecution execution = new JobExecution(
                1L,
                new JobInstance(1L, "settlementJob"),
                new JobParameters());
        execution.setStatus(status);
        execution.getExecutionContext().putString("settlementBatchId", batchId.toString());
        return execution;
    }
}
