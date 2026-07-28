package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.dto.SettlementCalculationReconciliationReport;
import com.prompthub.settlement.application.usecase.ReconcileSettlementCalculationUseCase;
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

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext) {
        SettlementCalculationReconciliationReport report =
                reconciliationUseCase.reconcile(
                        UUID.fromString(settlementBatchIdParam));
        if (!report.matched()) {
            throw new SettlementCalculationReconciliationException(
                    report.mismatchedSettlementIds());
        }
        return RepeatStatus.FINISHED;
    }
}
