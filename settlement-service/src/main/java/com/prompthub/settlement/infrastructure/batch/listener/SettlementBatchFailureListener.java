package com.prompthub.settlement.infrastructure.batch.listener;

import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SettlementBatchFailureListener implements JobExecutionListener {

    private static final String BATCH_ID_KEY = "settlementBatchId";

    private final SettlementBatchRepository settlementBatchRepository;

    @Override
    @Transactional
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            return;
        }

        String batchId = jobExecution.getExecutionContext().getString(BATCH_ID_KEY, null);
        if (batchId == null) {
            return;
        }

        settlementBatchRepository.findById(UUID.fromString(batchId)).ifPresent(batch -> {
            if (!batch.isProcessing()) {
                return;
            }
            batch.fail(resolveFailureReason(jobExecution));
            settlementBatchRepository.save(batch);
        });
    }

    private String resolveFailureReason(JobExecution jobExecution) {
        if (jobExecution.getAllFailureExceptions().isEmpty()) {
            return "정산 배치 실행 실패";
        }
        return jobExecution.getAllFailureExceptions().get(0).getMessage();
    }
}
