package com.prompthub.settlement.infrastructure.batch.launcher;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobParametersFactory {

    public JobParameters create(RunSettlementJobCommand command) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("periodStart", command.period().periodStart().toString(), true)
                .addString("periodEnd", command.period().periodEnd().toString(), true)
                .addLong("requestedAt", System.currentTimeMillis(), false)
                .addString("triggerType", command.triggerType().name(), false);
        if (command.actorId() != null) {
            builder.addString("actorId", command.actorId().toString(), false);
        }
        return builder.toJobParameters();
    }
}
