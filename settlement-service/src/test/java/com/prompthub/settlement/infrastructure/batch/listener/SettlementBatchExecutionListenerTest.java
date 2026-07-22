package com.prompthub.settlement.infrastructure.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import com.prompthub.settlement.global.exception.SettlementException;
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

class SettlementBatchExecutionListenerTest {

    private SettlementBatchRepository repository;
    private SettlementBatchExecutionListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementBatchRepository.class);
        listener = new SettlementBatchExecutionListener(repository);
    }

    @Test
    @DisplayName("재시작 실행 전 RETRY_REQUESTED 배치를 PROCESSING으로 저장한다")
    void beforeJob_retryRequestedBatch_startsRetry() {
        SettlementBatch batch = batch();
        batch.fail("첫 실행 실패");
        batch.requestRetry();
        JobExecution execution = execution(BatchStatus.STARTING, batch.getId());
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        listener.beforeJob(execution);

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PROCESSING);
        assertThat(batch.getFailureReason()).isNull();
        assertThat(batch.getExecutedAt()).isNull();
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("최초 실행처럼 배치 context가 없으면 beforeJob은 상태를 바꾸지 않는다")
    void beforeJob_withoutBatchContext_doesNothing() {
        JobExecution execution = executionWithoutBatchContext(BatchStatus.STARTING);

        listener.beforeJob(execution);

        then(repository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("재시작할 배치가 없으면 beforeJob에서 실행을 거부한다")
    void beforeJob_batchNotFound_throwsException() {
        UUID batchId = UUID.randomUUID();
        JobExecution execution = execution(BatchStatus.STARTING, batchId);
        given(repository.findById(batchId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> listener.beforeJob(execution))
                .isInstanceOf(SettlementException.class);

        then(repository).should(never()).save(any());
    }

    @Test
    @DisplayName("PROCESSING 배치의 Job이 실패하면 배치를 FAILED로 저장한다")
    void afterJob_failedProcessingBatch_marksFailed() {
        SettlementBatch batch = batch();
        JobExecution execution = execution(BatchStatus.FAILED, batch.getId());
        execution.addFailureException(new IllegalStateException("DB 연결 실패"));
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        listener.afterJob(execution);

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("DB 연결 실패");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("완료 후 outbox flush가 실패해도 COMPLETED 배치 상태는 덮어쓰지 않는다")
    void afterJob_flushFailsAfterCompletion_keepsCompletedBatch() {
        SettlementBatch batch = batch();
        batch.complete();
        JobExecution execution = execution(BatchStatus.FAILED, batch.getId());
        execution.addFailureException(new IllegalStateException("outbox DB failure"));
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        assertThatCode(() -> listener.afterJob(execution)).doesNotThrowAnyException();

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        then(repository).should(never()).save(batch);
    }

    @Test
    @DisplayName("정상 완료한 Job은 배치 실패 처리를 조회하지 않는다")
    void afterJob_completedJob_doesNothing() {
        SettlementBatch batch = batch();
        JobExecution execution = execution(BatchStatus.COMPLETED, batch.getId());

        listener.afterJob(execution);

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
        JobExecution execution = executionWithoutBatchContext(status);
        execution.getExecutionContext().putString("settlementBatchId", batchId.toString());
        return execution;
    }

    private JobExecution executionWithoutBatchContext(BatchStatus status) {
        JobExecution execution = new JobExecution(
                1L,
                new JobInstance(1L, "settlementJob"),
                new JobParameters());
        execution.setStatus(status);
        return execution;
    }
}
