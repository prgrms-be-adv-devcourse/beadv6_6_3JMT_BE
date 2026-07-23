package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.usecase.OutboxEventUseCase;
import com.prompthub.settlement.global.config.SettlementClockConfig;
import java.time.Instant;
import java.time.LocalDateTime;
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
public class RetryPendingOutboxTasklet implements Tasklet {

    private final OutboxEventUseCase outboxEventUseCase;

    @Value("#{jobParameters['requestedAt']}")
    private Long requestedAtParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (requestedAtParam == null) {
            throw new IllegalArgumentException("requestedAt Job Parameter는 필수입니다.");
        }
        LocalDateTime attemptedBefore = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(requestedAtParam),
                SettlementClockConfig.SETTLEMENT_ZONE);
        outboxEventUseCase.flushPendingBefore(attemptedBefore);
        return RepeatStatus.FINISHED;
    }
}
