package com.prompthub.settlement.application.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.application.dto.batch.CreateSettlementBatchCommand;
import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.model.batch.SettlementBatch;
import com.prompthub.settlement.domain.model.batch.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import com.prompthub.settlement.domain.model.batch.TriggerType;
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

class SettlementBatchLifecycleApplicationServiceTest {

    private SettlementBatchRepository repository;
    private SettlementBatchLifecycleApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementBatchRepository.class);
        service = new SettlementBatchLifecycleApplicationService(repository);
    }

    @Test
    @DisplayName("배치 생성 정보로 PROCESSING 배치를 저장하고 식별자를 반환한다")
    void create_savesProcessingBatchAndReturnsId() {
        UUID batchId = UUID.randomUUID();
        given(repository.save(any(SettlementBatch.class))).willAnswer(invocation -> {
            SettlementBatch batch = invocation.getArgument(0);
            ReflectionTestUtils.setField(batch, "id", batchId);
            return batch;
        });

        UUID result = service.create(new CreateSettlementBatchCommand(
                "SETTLE-20260713-20260719-SCHEDULED-101",
                11L,
                SettlementPeriod.of(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)),
                TriggerType.SCHEDULED));

        assertThat(result).isEqualTo(batchId);
    }

    @Test
    @DisplayName("처리 중인 배치를 완료 상태로 저장한다")
    void complete_processingBatch_savesCompletedState() {
        SettlementBatch batch = processingBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.complete(batch.getId());

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("처리 중인 배치 실패를 FAILED 상태로 저장한다")
    void fail_processingBatch_savesFailedState() {
        SettlementBatch batch = processingBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.fail(batch.getId(), "정산 계산 실패");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("정산 계산 실패");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("처리 중인 배치의 원천 대사 실패를 별도 업무 상태로 저장한다")
    void failReconciliation_processingBatch_savesReconciliationFailedState() {
        SettlementBatch batch = processingBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.failReconciliation(batch.getId(), "PAID_AMOUNT(order=2000, source=1000)");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.RECONCILIATION_FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("PAID_AMOUNT(order=2000, source=1000)");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("재시작 시작 전 실패는 RETRY_REQUESTED 배치를 FAILED로 복원한다")
    void fail_retryRequestedBatch_restoresFailedState() {
        SettlementBatch batch = retryRequestedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.fail(batch.getId(), "JobInstance 메타데이터 오류");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("JobInstance 메타데이터 오류");
        then(repository).should().save(batch);
    }

    @Test
    @DisplayName("이미 종료된 배치에 늦게 도착한 실패 처리는 상태를 바꾸지 않는다")
    void fail_completedBatch_doesNothing() {
        SettlementBatch batch = processingBatch();
        batch.complete();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.fail(batch.getId(), "늦게 전달된 오류");

        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        then(repository).should(never()).save(batch);
    }

    @Test
    @DisplayName("실패한 배치의 재시도를 요청하고 재시작 시 PROCESSING으로 전이한다")
    void retryLifecycle_transitionsAndSavesEachState() {
        SettlementBatch batch = failedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        service.requestRetry(batch.getId());
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.RETRY_REQUESTED);

        service.startRetry(batch.getId());
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PROCESSING);
        then(repository).should(org.mockito.Mockito.times(2)).save(batch);
    }

    @Test
    @DisplayName("RETRY_REQUESTED 배치의 JobInstance 식별자를 반환한다")
    void requireRetryJobInstanceId_validBatch_returnsId() {
        SettlementBatch batch = retryRequestedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        long result = service.requireRetryJobInstanceId(batch.getId());

        assertThat(result).isEqualTo(11L);
    }

    @Test
    @DisplayName("Delivery Step 실패로 Job만 실패한 완료 배치는 상태 변경 없이 재시작한다")
    void completedBatch_allowsDeliveryStepRestartWithoutStatusChange() {
        SettlementBatch batch = processingBatch();
        batch.complete();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        long result = service.requireRetryJobInstanceId(batch.getId());
        service.startRetry(batch.getId());

        assertThat(result).isEqualTo(11L);
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        then(repository).should(never()).save(batch);
    }

    @Test
    @DisplayName("RETRY_REQUESTED가 아닌 배치는 재시작할 수 없다")
    void requireRetryJobInstanceId_failedBatch_throwsException() {
        SettlementBatch batch = failedBatch();
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.requireRetryJobInstanceId(batch.getId()))
                .isInstanceOf(SettlementBatchInvalidStateException.class)
                .hasMessageContaining("expected=RETRY_REQUESTED");
    }

    @Test
    @DisplayName("JobInstance가 연결되지 않은 재시도 배치는 연결 오류를 던진다")
    void requireRetryJobInstanceId_unlinkedBatch_throwsException() {
        SettlementBatch batch = retryRequestedBatch();
        ReflectionTestUtils.setField(batch, "jobInstanceId", null);
        given(repository.findById(batch.getId())).willReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.requireRetryJobInstanceId(batch.getId()))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                SettlementErrorCode.SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED));
    }

    @Test
    @DisplayName("존재하지 않는 배치 상태 변경은 배치 없음 오류를 던진다")
    void complete_missingBatch_throwsException() {
        UUID batchId = UUID.randomUUID();
        given(repository.findById(batchId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(batchId))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND));
    }

    private SettlementBatch retryRequestedBatch() {
        SettlementBatch batch = failedBatch();
        batch.requestRetry();
        return batch;
    }

    private SettlementBatch failedBatch() {
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");
        return batch;
    }

    private SettlementBatch processingBatch() {
        SettlementBatch batch = SettlementBatch.start(
                "SETTLE-20260713-20260719-SCHEDULED-" + UUID.randomUUID(),
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                TriggerType.SCHEDULED);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        return batch;
    }
}
