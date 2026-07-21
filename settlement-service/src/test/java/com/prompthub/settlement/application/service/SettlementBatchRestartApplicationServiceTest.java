package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobRestarter;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementBatchRestartApplicationServiceTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000901");

    private SettlementBatchRetryStateService retryStateService;
    private SettlementJobRestarter jobRestarter;
    private SettlementBatchRestartApplicationService service;

    @BeforeEach
    void setUp() {
        retryStateService = mock(SettlementBatchRetryStateService.class);
        jobRestarter = mock(SettlementJobRestarter.class);
        service = new SettlementBatchRestartApplicationService(retryStateService, jobRestarter);
    }

    @Test
    @DisplayName("검증된 배치의 기존 JobInstance를 재시작한다")
    void restart_validBatch_delegatesToRestarter() {
        SettlementBatch batch = retryRequestedBatch();
        RestartSettlementBatchCommand command = command(batch.getId());
        SettlementJobResult expected = new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                LocalDateTime.of(2026, 7, 21, 10, 0));
        given(retryStateService.requireRetryRequested(batch.getId())).willReturn(batch);
        given(jobRestarter.restart(batch.getId(), 11L)).willReturn(expected);

        SettlementJobResult result = service.restart(command);

        assertThat(result).isEqualTo(expected);
        then(retryStateService).should(never()).restoreFailed(any(), any());
    }

    @Test
    @DisplayName("초기 배치 검증 오류는 상태 복원 없이 그대로 전달한다")
    void restart_initialValidationFails_doesNotRestore() {
        UUID batchId = UUID.randomUUID();
        RestartSettlementBatchCommand command = command(batchId);
        IllegalStateException validationFailure = new IllegalStateException("invalid state");
        given(retryStateService.requireRetryRequested(batchId)).willThrow(validationFailure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(validationFailure);

        then(jobRestarter).shouldHaveNoInteractions();
        then(retryStateService).should(never()).restoreFailed(any(), any());
    }

    @Test
    @DisplayName("새 JobExecution 시작 전 오류가 나면 배치를 FAILED로 복원하고 원래 예외를 던진다")
    void restart_restarterFails_restoresBatchAndRethrows() {
        SettlementBatch batch = retryRequestedBatch();
        RestartSettlementBatchCommand command = command(batch.getId());
        IllegalStateException restartFailure = new IllegalStateException("Job metadata missing");
        given(retryStateService.requireRetryRequested(batch.getId())).willReturn(batch);
        given(jobRestarter.restart(batch.getId(), 11L)).willThrow(restartFailure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(restartFailure);

        then(retryStateService).should().restoreFailed(batch.getId(), "Job metadata missing");
    }

    @Test
    @DisplayName("FAILED 복원도 실패하면 원래 재시작 예외에 복원 예외를 함께 보존한다")
    void restart_restoreAlsoFails_preservesBothFailures() {
        SettlementBatch batch = retryRequestedBatch();
        RestartSettlementBatchCommand command = command(batch.getId());
        IllegalStateException restartFailure = new IllegalStateException("restart failure");
        IllegalArgumentException restoreFailure = new IllegalArgumentException("restore failure");
        given(retryStateService.requireRetryRequested(batch.getId())).willReturn(batch);
        given(jobRestarter.restart(batch.getId(), 11L)).willThrow(restartFailure);
        willThrow(restoreFailure)
                .given(retryStateService)
                .restoreFailed(batch.getId(), "restart failure");

        assertThatThrownBy(() -> service.restart(command))
                .isSameAs(restartFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .containsExactly(restoreFailure));
    }

    @Test
    @DisplayName("재시작 예외 메시지가 없으면 기본 실패 사유로 복원한다")
    void restart_failureWithoutMessage_restoresDefaultReason() {
        SettlementBatch batch = retryRequestedBatch();
        RestartSettlementBatchCommand command = command(batch.getId());
        IllegalStateException restartFailure = new IllegalStateException();
        given(retryStateService.requireRetryRequested(batch.getId())).willReturn(batch);
        given(jobRestarter.restart(batch.getId(), 11L)).willThrow(restartFailure);

        assertThatThrownBy(() -> service.restart(command)).isSameAs(restartFailure);

        then(retryStateService).should().restoreFailed(
                batch.getId(),
                "정산 배치 재시작 실행 실패");
    }

    private RestartSettlementBatchCommand command(UUID batchId) {
        return new RestartSettlementBatchCommand(batchId, ACTOR_ID);
    }

    private SettlementBatch retryRequestedBatch() {
        SettlementBatch batch = SettlementBatch.start(
                "SETTLE-20260713-20260719-SCHEDULED-101",
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                TriggerType.SCHEDULED);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        batch.fail("첫 실행 실패");
        batch.requestRetry();
        return batch;
    }
}
