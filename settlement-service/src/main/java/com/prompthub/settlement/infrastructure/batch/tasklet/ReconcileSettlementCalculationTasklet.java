package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.dto.calculation.SettlementCalculationReconciliationReport;
import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.application.usecase.calculation.ReconcileSettlementCalculationUseCase;
import com.prompthub.settlement.domain.exception.SettlementCalculationReconciliationException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@StepScope
@RequiredArgsConstructor
public class ReconcileSettlementCalculationTasklet implements Tasklet {

    private final ReconcileSettlementCalculationUseCase reconciliationUseCase;
    private final SettlementBatchLifecycleUseCase settlementBatchLifecycleUseCase;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext) {
        UUID batchId = UUID.fromString(settlementBatchIdParam);
        SettlementCalculationReconciliationReport report =
                reconciliationUseCase.reconcile(batchId);
        if (!report.matched()) {
            SettlementCalculationReconciliationException exception =
                    new SettlementCalculationReconciliationException(
                            report.mismatchedSettlementIds());
            settlementBatchLifecycleUseCase.failReconciliation(
                    batchId,
                    exception.getMessage());
            throw exception;
        }
        return RepeatStatus.FINISHED;
    }
}
