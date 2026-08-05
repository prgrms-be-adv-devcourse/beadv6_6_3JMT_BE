package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.prompthub.settlement.application.dto.source.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.application.usecase.source.ReconcileSettlementSourceUseCase;
import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;

class ReconcileSettlementSourceTaskletTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26));
    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000639");

    @Test
    @DisplayName("대사 성공 결과를 StepExecutionContext에 기록하고 계산 진행을 허용한다")
    void execute_matched_writesContextWithoutFailureExit() throws Exception {
        ReconcileSettlementSourceUseCase reconcileUseCase =
                mock(ReconcileSettlementSourceUseCase.class);
        SettlementBatchLifecycleUseCase lifecycleUseCase =
                mock(SettlementBatchLifecycleUseCase.class);
        SettlementSourceAggregate aggregate = aggregate(2, "3000", 1, "500");
        given(reconcileUseCase.reconcile(PERIOD))
                .willReturn(SettlementSourceReconciliationResult.compare(aggregate, aggregate));
        StepExecution stepExecution = stepExecution();
        StepContribution contribution = mock(StepContribution.class);
        ReconcileSettlementSourceTasklet tasklet =
                tasklet(reconcileUseCase, lifecycleUseCase);

        RepeatStatus result = tasklet.execute(
                contribution,
                new ChunkContext(new StepContext(stepExecution)));

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecution.getExecutionContext().getString("reconciliationStatus"))
                .isEqualTo("MATCHED");
        assertThat(stepExecution.getExecutionContext().getLong("orderPaidCount")).isEqualTo(2);
        assertThat(stepExecution.getExecutionContext().getString("orderPaidAmount"))
                .isEqualTo("3000");
        assertThat(stepExecution.getExecutionContext().getLong("sourceRefundCount")).isEqualTo(1);
        assertThat(stepExecution.getExecutionContext().getString("sourceRefundAmount"))
                .isEqualTo("500");
        then(lifecycleUseCase).shouldHaveNoInteractions();
        then(contribution).should(never()).setExitStatus(
                org.mockito.ArgumentMatchers.any(ExitStatus.class));
    }

    @Test
    @DisplayName("대사 실패 결과를 Context에 기록하고 배치 상태와 ExitCode를 변경한다")
    void execute_mismatched_marksBatchAndFailureExit() throws Exception {
        ReconcileSettlementSourceUseCase reconcileUseCase =
                mock(ReconcileSettlementSourceUseCase.class);
        SettlementBatchLifecycleUseCase lifecycleUseCase =
                mock(SettlementBatchLifecycleUseCase.class);
        SettlementSourceAggregate order = aggregate(2, "3000", 0, "0");
        SettlementSourceAggregate source = aggregate(1, "1000", 0, "0");
        SettlementSourceReconciliationResult reconciliation =
                SettlementSourceReconciliationResult.compare(order, source);
        given(reconcileUseCase.reconcile(PERIOD)).willReturn(reconciliation);
        StepExecution stepExecution = stepExecution();
        StepContribution contribution = mock(StepContribution.class);
        ReconcileSettlementSourceTasklet tasklet =
                tasklet(reconcileUseCase, lifecycleUseCase);

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        assertThat(stepExecution.getExecutionContext().getString("reconciliationStatus"))
                .isEqualTo("MISMATCHED");
        assertThat(stepExecution.getExecutionContext().getLong("orderPaidCount")).isEqualTo(2);
        assertThat(stepExecution.getExecutionContext().getLong("sourcePaidCount")).isEqualTo(1);
        then(lifecycleUseCase).should()
                .failReconciliation(BATCH_ID, reconciliation.failureReason());
        ArgumentCaptor<ExitStatus> exitStatusCaptor = ArgumentCaptor.forClass(ExitStatus.class);
        then(contribution).should().setExitStatus(exitStatusCaptor.capture());
        assertThat(exitStatusCaptor.getValue().getExitCode())
                .isEqualTo("RECONCILIATION_FAILED");
        assertThat(exitStatusCaptor.getValue().getExitDescription())
                .contains(reconciliation.failureReason());
    }

    private ReconcileSettlementSourceTasklet tasklet(
            ReconcileSettlementSourceUseCase reconcileUseCase,
            SettlementBatchLifecycleUseCase lifecycleUseCase) {
        ReconcileSettlementSourceTasklet tasklet =
                new ReconcileSettlementSourceTasklet(reconcileUseCase, lifecycleUseCase);
        ReflectionTestUtils.setField(tasklet, "periodStartParam", "2026-07-20");
        ReflectionTestUtils.setField(tasklet, "periodEndParam", "2026-07-26");
        ReflectionTestUtils.setField(tasklet, "settlementBatchIdParam", BATCH_ID.toString());
        return tasklet;
    }

    private StepExecution stepExecution() {
        JobExecution jobExecution = new JobExecution(
                1L,
                new JobInstance(1L, "settlementJob"),
                new JobParameters());
        return new StepExecution(1L, "reconcileSettlementSourceStep", jobExecution);
    }

    private SettlementSourceAggregate aggregate(
            long paidCount,
            String paidAmount,
            long refundCount,
            String refundAmount) {
        return new SettlementSourceAggregate(
                paidCount,
                new BigDecimal(paidAmount),
                refundCount,
                new BigDecimal(refundAmount));
    }
}
