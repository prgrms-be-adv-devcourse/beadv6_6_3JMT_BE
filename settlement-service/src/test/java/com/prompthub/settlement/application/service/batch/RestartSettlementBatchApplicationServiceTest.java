package com.prompthub.settlement.application.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.application.dto.batch.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.batch.SettlementJobResult;
import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.application.usecase.batch.SettlementJobRestarter;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestartSettlementBatchApplicationServiceTest {

    private SettlementJobRestarter jobRestarter;
    private SettlementBatchLifecycleUseCase lifecycleUseCase;
    private RestartSettlementBatchApplicationService service;

    @BeforeEach
    void setUp() {
        jobRestarter = mock(SettlementJobRestarter.class);
        lifecycleUseCase = mock(SettlementBatchLifecycleUseCase.class);
        service = new RestartSettlementBatchApplicationService(
                jobRestarter,
                lifecycleUseCase);
    }

    @Test
    @DisplayName("재시도 가능한 배치의 기존 JobInstance를 재시작한다")
    void restart_validBatch_delegatesToRestarter() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = command(batchId);
        SettlementJobResult expected = jobResult();
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willReturn(11L);
        given(jobRestarter.restart(batchId, 11L)).willReturn(expected);

        assertThat(service.restart(command)).isEqualTo(expected);
        then(lifecycleUseCase).should(never()).fail(any(), any());
    }

    @Test
    @DisplayName("Job 재시작 실패 시 배치를 FAILED로 복원하고 원래 예외를 던진다")
    void restart_restarterFails_restoresBatchAndRethrows() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = command(batchId);
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
        RestartSettlementBatchCommand command = command(batchId);
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
        RestartSettlementBatchCommand command = command(batchId);
        IllegalStateException failure = new IllegalStateException("invalid state");
        given(lifecycleUseCase.requireRetryJobInstanceId(batchId)).willThrow(failure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(failure);
        then(lifecycleUseCase).should(never()).fail(any(), any());
    }

    @Test
    @DisplayName("FAILED 복원 실패는 원래 재시작 예외에 suppressed로 보존한다")
    void restart_restoreAlsoFails_preservesBothFailures() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = command(batchId);
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

    private RestartSettlementBatchCommand command(UUID batchId) {
        return new RestartSettlementBatchCommand(batchId, UUID.randomUUID());
    }

    private SettlementJobResult jobResult() {
        return new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0));
    }
}
