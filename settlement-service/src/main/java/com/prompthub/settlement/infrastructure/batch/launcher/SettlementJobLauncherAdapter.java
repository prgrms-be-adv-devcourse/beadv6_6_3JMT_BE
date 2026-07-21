package com.prompthub.settlement.infrastructure.batch.launcher;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementJobLauncher;
import com.prompthub.settlement.infrastructure.batch.config.SettlementBatchConfig;
import com.prompthub.settlement.infrastructure.batch.config.SettlementJobConfig;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobLauncherAdapter implements SettlementJobLauncher {

    private final JobOperator jobOperator;
    private final JobOperator asyncJobOperator;
    private final Job settlementJob;
    private final SettlementJobParametersFactory settlementJobParametersFactory;

    public SettlementJobLauncherAdapter(
            JobOperator jobOperator,
            @Qualifier(SettlementBatchConfig.ASYNC_JOB_OPERATOR) JobOperator asyncJobOperator,
            @Qualifier(SettlementJobConfig.SETTLEMENT_JOB_NAME) Job settlementJob,
            SettlementJobParametersFactory settlementJobParametersFactory) {
        this.jobOperator = jobOperator;
        this.asyncJobOperator = asyncJobOperator;
        this.settlementJob = settlementJob;
        this.settlementJobParametersFactory = settlementJobParametersFactory;
    }

    @Override
    public SettlementJobResult launch(RunSettlementJobCommand command) {
        JobParameters parameters = settlementJobParametersFactory.create(command);

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
