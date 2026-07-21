package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementBatchRetryStateServiceTest {

    private SettlementBatchRepository repository;
    private SettlementBatchRetryStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementBatchRepository.class);
        service = new SettlementBatchRetryStateService(repository);
    }

    @Test
    @DisplayName("RETRY_REQUESTED이며 JobInstance가 연결된 배치를 반환한다")
    void requireRetryRequested_validBatch_returnsBatch() {
        SettlementBatch batch = retryRequestedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        SettlementBatch result = service.requireRetryRequested(batch.getId());

        assertThat(result).isSameAs(batch);
    }

    @Test
    @DisplayName("재시작할 배치가 없으면 배치 없음 오류를 던진다")
    void requireRetryRequested_batchNotFound_throwsException() {
        UUID batchId = UUID.randomUUID();
        given(repository.findById(batchId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireRetryRequested(batchId))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND));
    }

    @Test
    @DisplayName("RETRY_REQUESTED가 아닌 배치는 재시작할 수 없다")
    void requireRetryRequested_failedBatch_throwsException() {
        SettlementBatch batch = failedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.requireRetryRequested(batch.getId()))
                .isInstanceOf(SettlementBatchInvalidStateException.class)
                .hasMessageContaining("expected=RETRY_REQUESTED")
                .hasMessageContaining("current=FAILED");
    }

    @Test
    @DisplayName("JobInstance가 연결되지 않은 레거시 배치는 재시작할 수 없다")
    void requireRetryRequested_unlinkedLegacyBatch_throwsException() {
        SettlementBatch batch = retryRequestedBatch();
        ReflectionTestUtils.setField(batch, "jobInstanceId", null);
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.requireRetryRequested(batch.getId()))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED));
    }

    @Test
    @DisplayName("재시작 시작 전 오류가 나면 RETRY_REQUESTED 배치를 FAILED로 복원한다")
    void restoreFailed_retryRequestedBatch_savesFailedState() {
        SettlementBatch batch = retryRequestedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.restoreFailed(batch.getId(), "JobInstance 메타데이터 오류");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("JobInstance 메타데이터 오류");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("새 JobExecution이 이미 시작된 PROCESSING 배치는 FAILED로 되돌리지 않는다")
    void restoreFailed_processingBatch_doesNothing() {
        SettlementBatch batch = retryRequestedBatch();
        batch.startRetry();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.restoreFailed(batch.getId(), "늦게 전달된 오류");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PROCESSING);
        then(repository).should(never()).save(batch);
    }

    private SettlementBatch retryRequestedBatch() {
        SettlementBatch batch = failedBatch();
        batch.requestRetry();
        return batch;
    }

    private SettlementBatch failedBatch() {
        SettlementBatch batch = SettlementBatch.start(
                "SETTLE-20260713-20260719-SCHEDULED-101",
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                TriggerType.SCHEDULED);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        batch.fail("첫 실행 실패");
        return batch;
    }
}
