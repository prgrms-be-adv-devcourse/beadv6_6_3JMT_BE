package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
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

    private static final DateTimeFormatter BATCH_NO_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String BATCH_ID_KEY = "settlementBatchId";

    private final SettlementBatchRepository settlementBatchRepository;

    @Value("#{jobParameters['period']}")
    private String periodParam;

    @Value("#{jobParameters['triggerType']}")
    private String triggerTypeParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        YearMonth period = YearMonth.parse(periodParam);
        TriggerType triggerType = TriggerType.valueOf(triggerTypeParam);

        long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        String batchNo = generateBatchNo(period, triggerType, jobExecutionId);

        SettlementBatch saved = settlementBatchRepository.save(
                SettlementBatch.start(batchNo, period.atDay(1), period.atEndOfMonth(), triggerType));

        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString(BATCH_ID_KEY, saved.getId().toString());

        return RepeatStatus.FINISHED;
    }

    private String generateBatchNo(YearMonth period, TriggerType triggerType, long jobExecutionId) {
        return "SETTLE-%s-%s-%d".formatted(period.format(BATCH_NO_MONTH), triggerType.name(), jobExecutionId);
    }
}
