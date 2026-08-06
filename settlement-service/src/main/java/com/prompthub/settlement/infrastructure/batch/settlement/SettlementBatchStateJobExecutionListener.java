package com.prompthub.settlement.infrastructure.batch.settlement;

import com.prompthub.settlement.application.usecase.batch.SettlementBatchLifecycleUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementBatchStateJobExecutionListener implements JobExecutionListener {

    private static final String BATCH_ID_KEY = "settlementBatchId";

    private final SettlementBatchLifecycleUseCase settlementBatchLifecycleUseCase;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String batchId = jobExecution.getExecutionContext().getString(BATCH_ID_KEY, null);
        if (batchId == null) {
            return;
        }

        settlementBatchLifecycleUseCase.startRetry(UUID.fromString(batchId));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            return;
        }

        String batchId = jobExecution.getExecutionContext().getString(BATCH_ID_KEY, null);
        if (batchId == null) {
            return;
        }

        settlementBatchLifecycleUseCase.fail(
                UUID.fromString(batchId),
                resolveFailureReason(jobExecution));
    }

    private String resolveFailureReason(JobExecution jobExecution) {
        if (jobExecution.getAllFailureExceptions().isEmpty()) {
            return "정산 배치 실행 실패";
        }
        return jobExecution.getAllFailureExceptions().get(0).getMessage();
    }
}
