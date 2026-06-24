package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.util.UUID;
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
public class CompleteSettlementBatchTasklet implements Tasklet {

    private final SettlementBatchRepository settlementBatchRepository;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID settlementBatchId = UUID.fromString(settlementBatchIdParam);
        SettlementBatch batch = settlementBatchRepository.findById(settlementBatchId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND));
        batch.complete();
        settlementBatchRepository.save(batch);
        return RepeatStatus.FINISHED;
    }
}
