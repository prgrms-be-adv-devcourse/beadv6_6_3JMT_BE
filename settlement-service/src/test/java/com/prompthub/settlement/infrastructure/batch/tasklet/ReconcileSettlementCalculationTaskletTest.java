package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.SettlementCalculationReconciliationReport;
import com.prompthub.settlement.application.usecase.ReconcileSettlementCalculationUseCase;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.domain.exception.SettlementCalculationReconciliationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;

class ReconcileSettlementCalculationTaskletTest {

    @Test
    @DisplayName("계산 대사가 모두 일치하면 Step을 완료한다")
    void execute_allMatched_finishes() throws Exception {
        UUID batchId = UUID.randomUUID();
        ReconcileSettlementCalculationUseCase useCase =
                mock(ReconcileSettlementCalculationUseCase.class);
        SettlementBatchLifecycleUseCase lifecycleUseCase =
                mock(SettlementBatchLifecycleUseCase.class);
        ReconcileSettlementCalculationTasklet tasklet =
                new ReconcileSettlementCalculationTasklet(useCase, lifecycleUseCase);
        ReflectionTestUtils.setField(
                tasklet, "settlementBatchIdParam", batchId.toString());
        given(useCase.reconcile(batchId))
                .willReturn(new SettlementCalculationReconciliationReport(
                        2, List.of()));

        RepeatStatus result = tasklet.execute(null, null);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        then(useCase).should().reconcile(batchId);
        then(lifecycleUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("계산 대사가 하나라도 불일치하면 실패 settlement ID와 함께 Step을 실패시킨다")
    void execute_mismatched_throws() {
        UUID batchId = UUID.randomUUID();
        UUID mismatchedSettlementId = UUID.randomUUID();
        ReconcileSettlementCalculationUseCase useCase =
                mock(ReconcileSettlementCalculationUseCase.class);
        SettlementBatchLifecycleUseCase lifecycleUseCase =
                mock(SettlementBatchLifecycleUseCase.class);
        ReconcileSettlementCalculationTasklet tasklet =
                new ReconcileSettlementCalculationTasklet(useCase, lifecycleUseCase);
        ReflectionTestUtils.setField(
                tasklet, "settlementBatchIdParam", batchId.toString());
        given(useCase.reconcile(batchId))
                .willReturn(new SettlementCalculationReconciliationReport(
                        2, List.of(mismatchedSettlementId)));

        assertThatThrownBy(() -> tasklet.execute(null, null))
                .isInstanceOf(SettlementCalculationReconciliationException.class)
                .hasMessageContaining(mismatchedSettlementId.toString());
        then(lifecycleUseCase).should().failReconciliation(
                batchId,
                new SettlementCalculationReconciliationException(
                        List.of(mismatchedSettlementId)).getMessage());
    }
}
