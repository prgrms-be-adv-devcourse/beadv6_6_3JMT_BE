package com.prompthub.settlement.infrastructure.batch;

import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.port.SettlementJobQuery;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementJobQueryAdapter implements SettlementJobQuery {

    private final JobRepository jobRepository;

    @Override
    public Optional<SettlementJobStatusResult> findByJobExecutionId(Long jobExecutionId) {
        JobExecution execution = jobRepository.getJobExecution(jobExecutionId);
        if (execution == null) {
            return Optional.empty();
        }
        return Optional.of(new SettlementJobStatusResult(
                execution.getId(),
                execution.getJobInstance().getJobName(),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                execution.getStartTime(),
                execution.getEndTime(),
                resolveFailureMessage(execution)));
    }

    private String resolveFailureMessage(JobExecution execution) {
        if (execution.getAllFailureExceptions().isEmpty()) {
            return null;
        }
        return execution.getAllFailureExceptions().get(0).getMessage();
    }
}
