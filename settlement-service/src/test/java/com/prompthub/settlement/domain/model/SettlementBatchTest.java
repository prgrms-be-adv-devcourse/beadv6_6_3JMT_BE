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
        assertThat(batch.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(batch.getExecutedAt()).isNull();
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
}
