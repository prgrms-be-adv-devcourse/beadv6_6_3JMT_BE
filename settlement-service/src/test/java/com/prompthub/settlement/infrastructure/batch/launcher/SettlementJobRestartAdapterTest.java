package com.prompthub.settlement.infrastructure.batch.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;

class SettlementJobRestartAdapterTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final long JOB_INSTANCE_ID = 11L;

    private JobRepository jobRepository;
    private JobOperator jobOperator;
    private SettlementJobRestartAdapter adapter;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobOperator = mock(JobOperator.class);
        adapter = new SettlementJobRestartAdapter(jobRepository, jobOperator);
    }

    @Test
    @DisplayName("JobInstance가 없으면 재시작하지 않는다")
    void restart_jobInstanceNotFound_throwsException() {
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(null);

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);

        then(jobOperator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("settlementJob이 아닌 JobInstance는 재시작하지 않는다")
    void restart_wrongJobName_throwsException() {
        JobInstance instance = new JobInstance(JOB_INSTANCE_ID, "otherJob");
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);

        then(jobOperator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최근 JobExecution이 없으면 재시작하지 않는다")
    void restart_lastExecutionNotFound_throwsException() {
        JobInstance instance = settlementJobInstance();
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);
        given(jobRepository.getLastJobExecution(instance)).willReturn(null);

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);

        then(jobOperator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최근 JobExecution이 FAILED가 아니면 재시작하지 않는다")
    void restart_lastExecutionStarted_throwsException() {
        JobInstance instance = settlementJobInstance();
        JobExecution execution = execution(101L, instance, BatchStatus.STARTED, BATCH_ID);
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);
        given(jobRepository.getLastJobExecution(instance)).willReturn(execution);

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_NOT_RESTARTABLE);

        then(jobOperator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최근 실패 실행의 배치 context가 요청 배치와 다르면 재시작하지 않는다")
    void restart_batchContextMismatch_throwsException() {
        JobInstance instance = settlementJobInstance();
        JobExecution execution = execution(
                101L,
                instance,
                BatchStatus.FAILED,
                UUID.fromString("00000000-0000-0000-0000-000000000802"));
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);
        given(jobRepository.getLastJobExecution(instance)).willReturn(execution);

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_BATCH_MISMATCH);

        then(jobOperator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최근 FAILED JobExecution을 Spring Batch 6 API로 재시작한다")
    void restart_validFailedExecution_returnsRestartResult() throws Exception {
        JobInstance instance = settlementJobInstance();
        JobExecution failed = execution(101L, instance, BatchStatus.FAILED, BATCH_ID);
        JobExecution restarted = execution(102L, instance, BatchStatus.COMPLETED, BATCH_ID);
        restarted.setStartTime(LocalDateTime.of(2026, 7, 21, 10, 0));
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);
        given(jobRepository.getLastJobExecution(instance)).willReturn(failed);
        given(jobOperator.restart(failed)).willReturn(restarted);

        SettlementJobResult result = adapter.restart(BATCH_ID, JOB_INSTANCE_ID);

        assertThat(result).isEqualTo(new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0)));
        then(jobOperator).should().restart(failed);
    }

    @Test
    @DisplayName("Spring Batch 재시작 오류는 정산 Job 실행 오류로 변환한다")
    void restart_operatorFails_wrapsException() throws Exception {
        JobInstance instance = settlementJobInstance();
        JobExecution failed = execution(101L, instance, BatchStatus.FAILED, BATCH_ID);
        given(jobRepository.getJobInstance(JOB_INSTANCE_ID)).willReturn(instance);
        given(jobRepository.getLastJobExecution(instance)).willReturn(failed);
        given(jobOperator.restart(failed)).willThrow(new JobRestartException("already running"));

        assertErrorCode(
                () -> adapter.restart(BATCH_ID, JOB_INSTANCE_ID),
                SettlementErrorCode.SETTLEMENT_JOB_EXECUTION_FAILED);
    }

    private JobInstance settlementJobInstance() {
        return new JobInstance(JOB_INSTANCE_ID, "settlementJob");
    }

    private JobExecution execution(
            long executionId,
            JobInstance instance,
            BatchStatus status,
            UUID batchId) {
        JobExecution execution = new JobExecution(executionId, instance, new JobParameters());
        execution.setStatus(status);
        execution.getExecutionContext().putString("settlementBatchId", batchId.toString());
        return execution;
    }

    private void assertErrorCode(Runnable action, SettlementErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
