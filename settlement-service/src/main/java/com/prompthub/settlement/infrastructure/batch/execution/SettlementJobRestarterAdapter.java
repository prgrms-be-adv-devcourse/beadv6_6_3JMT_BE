package com.prompthub.settlement.infrastructure.batch.execution;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobRestarter;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.infrastructure.batch.config.SettlementJobConfig;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementJobRestarterAdapter implements SettlementJobRestarter {

    private static final String BATCH_ID_KEY = "settlementBatchId";

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;

    @Override
    public SettlementJobResult restart(UUID batchId, long jobInstanceId) {
        JobInstance jobInstance = requireSettlementJobInstance(jobInstanceId);
        JobExecution lastExecution = requireLastExecution(jobInstance);
        requireFailed(lastExecution);
        requireMatchingBatch(lastExecution, batchId);

        try {
            JobExecution restarted = jobOperator.restart(lastExecution);
            return new SettlementJobResult(
                    restarted.getId(),
                    restarted.getJobInstance().getJobName(),
                    restarted.getStatus().name(),
                    restarted.getStartTime());
        } catch (Exception exception) {
            throw new SettlementException(
                    SettlementErrorCode.SETTLEMENT_JOB_EXECUTION_FAILED,
                    exception);
        }
    }

    private JobInstance requireSettlementJobInstance(long jobInstanceId) {
        JobInstance jobInstance = jobRepository.getJobInstance(jobInstanceId);
        if (jobInstance == null
                || !SettlementJobConfig.SETTLEMENT_JOB_NAME.equals(jobInstance.getJobName())) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);
        }
        return jobInstance;
    }

    private JobExecution requireLastExecution(JobInstance jobInstance) {
        JobExecution lastExecution = jobRepository.getLastJobExecution(jobInstance);
        if (lastExecution == null) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND);
        }
        return lastExecution;
    }

    private void requireFailed(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.FAILED) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_NOT_RESTARTABLE);
        }
    }

    private void requireMatchingBatch(JobExecution execution, UUID batchId) {
        String executionBatchId = execution.getExecutionContext().getString(BATCH_ID_KEY, null);
        if (!batchId.toString().equals(executionBatchId)) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_BATCH_MISMATCH);
        }
    }
}
