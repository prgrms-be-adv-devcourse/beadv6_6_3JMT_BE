package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.SettlementBatchInvalidStateException;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementBatchTest {

    private SettlementBatch processingBatch() {
        return SettlementBatch.start(
                "B-001",
                41L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                TriggerType.SCHEDULED);
    }

    @Test
    @DisplayName("배치를 시작하면 초기 상태는 PROCESSING이다")
    void start_initialStatusIsProcessing() {
        // when
        SettlementBatch batch = processingBatch();

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PROCESSING);
        assertThat(batch.getBatchNo()).isEqualTo("B-001");
        assertThat(batch.getJobInstanceId()).isEqualTo(41L);
        assertThat(batch.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(batch.getExecutedAt()).isNull();
        assertThat(batch.isProcessing()).isTrue();
    }

    @Test
    @DisplayName("PROCESSING 배치를 완료하면 COMPLETED가 되고 실행 시각이 기록된다")
    void complete_fromProcessing_becomesCompleted() {
        // given
        SettlementBatch batch = processingBatch();

        // when
        batch.complete();

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        assertThat(batch.getExecutedAt()).isNotNull();
        assertThat(batch.isProcessing()).isFalse();
    }

    @Test
    @DisplayName("PROCESSING 상태가 아닌 배치는 완료할 수 없다")
    void complete_notProcessing_throwsException() {
        // given
        SettlementBatch batch = processingBatch();
        batch.complete(); // PROCESSING -> COMPLETED

        // when & then
        assertThatThrownBy(batch::complete)
                .isInstanceOf(SettlementBatchInvalidStateException.class);
    }

    @Test
    @DisplayName("PROCESSING 배치를 실패 처리하면 FAILED가 되고 사유와 실행 시각이 기록된다")
    void fail_fromProcessing_becomesFailed() {
        // given
        SettlementBatch batch = processingBatch();

        // when
        batch.fail("DB 연결 실패");

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("DB 연결 실패");
        assertThat(batch.getExecutedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 완료된 배치는 실패 처리할 수 없다")
    void fail_alreadyCompleted_throwsException() {
        // given
        SettlementBatch batch = processingBatch();
        batch.complete();

        // when & then
        assertThatThrownBy(() -> batch.fail("뒤늦은 실패"))
                .isInstanceOf(SettlementBatchInvalidStateException.class);
    }

    @Test
    @DisplayName("FAILED 배치에 재시작을 요청하면 RETRY_REQUESTED가 된다")
    void requestRetry_fromFailed_becomesRetryRequested() {
        // given
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");

        // when
        batch.requestRetry();

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.RETRY_REQUESTED);
        assertThat(batch.isRetryRequested()).isTrue();
    }

    @Test
    @DisplayName("재시작을 시작하면 PROCESSING이 되고 이전 실패 정보를 비운다")
    void startRetry_fromRetryRequested_clearsPreviousFailure() {
        // given
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");
        batch.requestRetry();

        // when
        batch.startRetry();

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PROCESSING);
        assertThat(batch.getFailureReason()).isNull();
        assertThat(batch.getExecutedAt()).isNull();
        assertThat(batch.getJobInstanceId()).isEqualTo(41L);
    }

    @Test
    @DisplayName("재시작 시작 전 오류가 나면 FAILED로 복원하고 새 사유를 기록한다")
    void restoreFailed_fromRetryRequested_recordsNewFailure() {
        // given
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");
        batch.requestRetry();

        // when
        batch.restoreFailed("재시작 실행 오류");

        // then
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).isEqualTo("재시작 실행 오류");
        assertThat(batch.getExecutedAt()).isNotNull();
    }

    @Test
    @DisplayName("FAILED가 아닌 배치에는 재시작을 요청할 수 없다")
    void requestRetry_notFailed_throwsException() {
        SettlementBatch batch = processingBatch();

        assertThatThrownBy(batch::requestRetry)
                .isInstanceOf(SettlementBatchInvalidStateException.class)
                .hasMessageContaining("expected=FAILED")
                .hasMessageContaining("current=PROCESSING");
    }

    @Test
    @DisplayName("RETRY_REQUESTED가 아닌 배치는 재시작을 시작할 수 없다")
    void startRetry_notRetryRequested_throwsException() {
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");

        assertThatThrownBy(batch::startRetry)
                .isInstanceOf(SettlementBatchInvalidStateException.class)
                .hasMessageContaining("expected=RETRY_REQUESTED")
                .hasMessageContaining("current=FAILED");
    }

    @Test
    @DisplayName("RETRY_REQUESTED가 아닌 배치는 FAILED로 복원할 수 없다")
    void restoreFailed_notRetryRequested_throwsException() {
        SettlementBatch batch = processingBatch();
        batch.fail("첫 실행 실패");

        assertThatThrownBy(() -> batch.restoreFailed("재시작 실행 오류"))
                .isInstanceOf(SettlementBatchInvalidStateException.class)
                .hasMessageContaining("expected=RETRY_REQUESTED")
                .hasMessageContaining("current=FAILED");
    }

    @Test
    @DisplayName("JobInstance ID는 양수여야 한다")
    void start_nonPositiveJobInstanceId_throwsException() {
        assertThatThrownBy(() -> SettlementBatch.start(
                "B-001",
                0L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                TriggerType.SCHEDULED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobInstanceId");
    }

    @Test
    @DisplayName("실패 사유는 DB 컬럼 길이인 1000자로 제한한다")
    void fail_longReason_truncatesToColumnLength() {
        SettlementBatch batch = processingBatch();

        batch.fail("실".repeat(1_001));

        assertThat(batch.getFailureReason()).hasSize(1_000);
    }
}
