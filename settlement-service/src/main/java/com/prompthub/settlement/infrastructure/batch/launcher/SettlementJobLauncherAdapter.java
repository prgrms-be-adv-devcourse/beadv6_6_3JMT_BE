package com.prompthub.settlement.infrastructure.batch.launcher;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.global.config.SettlementBatchConfig;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobLauncherAdapter implements SettlementJobLauncher {

    private final JobOperator jobOperator;
    private final JobOperator asyncJobOperator;
    private final Job settlementJob;

    public SettlementJobLauncherAdapter(
            JobOperator jobOperator,
            @Qualifier(SettlementBatchConfig.ASYNC_JOB_OPERATOR) JobOperator asyncJobOperator,
            Job settlementJob) {
        this.jobOperator = jobOperator;
        this.asyncJobOperator = asyncJobOperator;
        this.settlementJob = settlementJob;
    }

    @Override
    public SettlementJobResult launch(RunSettlementJobCommand command) {
        JobParameters parameters = new JobParametersBuilder()
                .addString("period", command.period().toString())
                .addString("actorId", String.valueOf(command.actorId()))
                .addString("triggerType", command.triggerType().name())
                .addLong("requestedAt", System.currentTimeMillis())
                .toJobParameters();

        // 수동 실행은 비동기로 띄워 요청 스레드를 즉시 돌려준다. 스케줄은 동기로 끝까지 기다린다.
        JobOperator operator = command.triggerType() == TriggerType.MANUAL ? asyncJobOperator : jobOperator;

        try {
            JobExecution execution = operator.start(settlementJob, parameters);
            return new SettlementJobResult(
                    execution.getId(),
                    execution.getJobInstance().getJobName(),
                    execution.getStatus().name(),
                    execution.getStartTime());
        } catch (Exception e) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_EXECUTION_FAILED, e);
        }
    }
}
