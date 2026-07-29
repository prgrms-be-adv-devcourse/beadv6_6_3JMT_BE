package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.usecase.ReconcileSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@StepScope
@RequiredArgsConstructor
public class ReconcileSettlementSourceTasklet implements Tasklet {

    public static final String RECONCILIATION_FAILED_EXIT_CODE = "RECONCILIATION_FAILED";

    private final ReconcileSettlementSourceUseCase reconcileSettlementSourceUseCase;
    private final SettlementBatchLifecycleUseCase settlementBatchLifecycleUseCase;

    @Value("#{jobParameters['periodStart']}")
    private String periodStartParam;

    @Value("#{jobParameters['periodEnd']}")
    private String periodEndParam;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SettlementSourceReconciliationResult result =
                reconcileSettlementSourceUseCase.reconcile(period());
        writeContext(
                chunkContext.getStepContext().getStepExecution().getExecutionContext(),
                result);
        if (result.matched()) {
            return RepeatStatus.FINISHED;
        }

        settlementBatchLifecycleUseCase.failReconciliation(
                UUID.fromString(settlementBatchIdParam),
                result.failureReason());
        contribution.setExitStatus(new ExitStatus(RECONCILIATION_FAILED_EXIT_CODE)
                .addExitDescription(result.failureReason()));
        return RepeatStatus.FINISHED;
    }

    private SettlementPeriod period() {
        return SettlementPeriod.of(
                LocalDate.parse(periodStartParam),
                LocalDate.parse(periodEndParam));
    }

    private void writeContext(
            ExecutionContext context,
            SettlementSourceReconciliationResult result) {
        context.putString(
                "reconciliationStatus",
                result.matched() ? "MATCHED" : "MISMATCHED");
        writeAggregate(context, "order", result.orderAggregate());
        writeAggregate(context, "source", result.sourceAggregate());
    }

    private void writeAggregate(
            ExecutionContext context,
            String prefix,
            SettlementSourceAggregate aggregate) {
        context.putLong(prefix + "PaidCount", aggregate.paidCount());
        context.putString(prefix + "PaidAmount", format(aggregate.paidAmount()));
        context.putLong(prefix + "RefundCount", aggregate.refundCount());
        context.putString(prefix + "RefundAmount", format(aggregate.refundAmount()));
    }

    private String format(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
