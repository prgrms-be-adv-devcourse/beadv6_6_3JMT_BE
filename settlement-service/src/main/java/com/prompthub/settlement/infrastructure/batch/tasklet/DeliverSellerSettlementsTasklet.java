package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.usecase.delivery.SettlementDeliveryUseCase;
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
public class DeliverSellerSettlementsTasklet implements Tasklet {

    private final SettlementDeliveryUseCase deliveryUseCase;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    @Override
    public RepeatStatus execute(
            StepContribution contribution, ChunkContext chunkContext) {
        deliveryUseCase.deliverBatch(UUID.fromString(settlementBatchIdParam));
        return RepeatStatus.FINISHED;
    }
}
