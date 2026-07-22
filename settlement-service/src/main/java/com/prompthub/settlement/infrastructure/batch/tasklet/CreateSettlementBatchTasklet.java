package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
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
public class CreateSettlementBatchTasklet implements Tasklet {

    private static final String BATCH_ID_KEY = "settlementBatchId";

    private final SettlementBatchRepository settlementBatchRepository;

    @Value("#{jobParameters['periodStart']}")
    private String periodStartParam;

    @Value("#{jobParameters['periodEnd']}")
    private String periodEndParam;

    @Value("#{jobParameters['triggerType']}")
    private String triggerTypeParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SettlementPeriod period = period();
        TriggerType triggerType = TriggerType.valueOf(triggerTypeParam);

        JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();
        long jobExecutionId = jobExecution.getId();
        long jobInstanceId = jobExecution.getJobInstance().getInstanceId();
        String batchNo = generateBatchNo(period, triggerType, jobExecutionId);

        SettlementBatch saved = settlementBatchRepository.save(
                SettlementBatch.start(
                        batchNo,
                        jobInstanceId,
                        period.periodStart(),
                        period.periodEnd(),
                        triggerType));

        jobExecution.getExecutionContext().putString(BATCH_ID_KEY, saved.getId().toString());

        return RepeatStatus.FINISHED;
    }

    private SettlementPeriod period() {
        return SettlementPeriod.of(LocalDate.parse(periodStartParam), LocalDate.parse(periodEndParam));
    }

    private String generateBatchNo(SettlementPeriod period, TriggerType triggerType, long jobExecutionId) {
        return "SETTLE-%s-%s-%s-%d".formatted(
                period.periodStart().format(DateTimeFormatter.BASIC_ISO_DATE),
                period.periodEnd().format(DateTimeFormatter.BASIC_ISO_DATE),
                triggerType.name(),
                jobExecutionId);
    }
}
