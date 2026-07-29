package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.application.port.SettlementJobQuery;
import com.prompthub.settlement.application.port.SettlementJobRestarter;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementBatchExecutionApplicationServiceTest {

    private SettlementJobLauncher jobLauncher;
    private SettlementJobRestarter jobRestarter;
    private SettlementJobQuery jobQuery;
    private SettlementBatchLifecycleUseCase lifecycleUseCase;
    private SettlementBatchExecutionApplicationService service;

    @BeforeEach
    void setUp() {
        jobLauncher = mock(SettlementJobLauncher.class);
        jobRestarter = mock(SettlementJobRestarter.class);
        jobQuery = mock(SettlementJobQuery.class);
        lifecycleUseCase = mock(SettlementBatchLifecycleUseCase.class);
        service = new SettlementBatchExecutionApplicationService(
                jobLauncher,
                jobRestarter,
                jobQuery,
                lifecycleUseCase);
    }

    @Test
    @DisplayName("배치 실행 요청을 Job 실행 포트에 위임한다")
    void run_delegatesToLauncher() {
        RunSettlementBatchCommand command = RunSettlementBatchCommand.scheduled(
                SettlementPeriod.of(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)));
        SettlementJobResult expected = jobResult();
        given(jobLauncher.launch(command)).willReturn(expected);

        assertThat(service.run(command)).isEqualTo(expected);
    }

    @Test
    @DisplayName("배치 실행 상태를 Job 조회 포트에서 반환한다")
    void getStatus_existingExecution_returnsStatus() {
        SettlementJobStatusResult expected = new SettlementJobStatusResult(
                101L,
                "settlementJob",
                "COMPLETED",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0),
                LocalDateTime.of(2026, 7, 21, 10, 1),
                null);
        given(jobQuery.findByJobExecutionId(101L)).willReturn(Optional.of(expected));

        assertThat(service.getStatus(101L)).isEqualTo(expected);
    }

    @Test
    @DisplayName("배치 실행 상태가 없으면 Job 없음 오류를 던진다")
    void getStatus_missingExecution_throwsException() {
        given(jobQuery.findByJobExecutionId(101L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(101L))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }

    @Test
    @DisplayName("재시도 가능한 배치의 기존 JobInstance를 재시작한다")
    void restart_validBatch_delegatesToRestarter() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = new RestartSettlementBatchCommand(
                batchId,
                UUID.randomUUID());
        SettlementJobResult expected = jobResult();
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willReturn(11L);
        given(jobRestarter.restart(batchId, 11L)).willReturn(expected);

        assertThat(service.restart(command)).isEqualTo(expected);

        then(lifecycleUseCase).should(never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Job 재시작 실패 시 배치를 FAILED로 복원하고 원래 예외를 던진다")
    void restart_restarterFails_restoresBatchAndRethrows() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = new RestartSettlementBatchCommand(
                batchId,
                UUID.randomUUID());
        IllegalStateException failure = new IllegalStateException("Job metadata missing");
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willReturn(11L);
        given(jobRestarter.restart(batchId, 11L)).willThrow(failure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(failure);

        then(lifecycleUseCase).should().fail(batchId, "Job metadata missing");
    }

    @Test
    @DisplayName("JobInstance 미연결 오류도 배치를 FAILED로 복원한다")
    void restart_unlinkedBatch_restoresFailedAndRethrows() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = new RestartSettlementBatchCommand(
                batchId,
                UUID.randomUUID());
        SettlementException failure = new SettlementException(
                SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED);
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willThrow(failure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(failure);

        then(lifecycleUseCase).should().fail(
                batchId,
                SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED.getMessage());
        then(jobRestarter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("초기 배치 상태 검증 오류는 상태 복원 없이 그대로 전달한다")
    void restart_initialValidationFails_doesNotRestore() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = new RestartSettlementBatchCommand(
                batchId,
                UUID.randomUUID());
        IllegalStateException failure = new IllegalStateException("invalid state");
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willThrow(failure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(failure);

        then(lifecycleUseCase).should(never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("FAILED 복원 실패는 원래 재시작 예외에 suppressed로 보존한다")
    void restart_restoreAlsoFails_preservesBothFailures() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = new RestartSettlementBatchCommand(
                batchId,
                UUID.randomUUID());
        IllegalStateException restartFailure = new IllegalStateException("restart failure");
        IllegalArgumentException restoreFailure = new IllegalArgumentException("restore failure");
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willReturn(11L);
        given(jobRestarter.restart(batchId, 11L)).willThrow(restartFailure);
        willThrow(restoreFailure).given(lifecycleUseCase).fail(batchId, "restart failure");

        assertThatThrownBy(() -> service.restart(command))
                .isSameAs(restartFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .containsExactly(restoreFailure));
    }

    private SettlementJobResult jobResult() {
        return new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0));
    }
}
