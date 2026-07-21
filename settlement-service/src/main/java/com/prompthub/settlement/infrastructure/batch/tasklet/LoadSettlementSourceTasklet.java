package com.prompthub.settlement.infrastructure.batch.tasklet;

import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정산 배치의 첫 스텝. order-service 에서 정산 대상 라인을 gRPC 로 bulk 조회해
 * SettlementSourceLine 으로 적재한다(#260). 이후 스텝이 적재된 소스 라인을 읽어 정산한다.
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class LoadSettlementSourceTasklet implements Tasklet {

    private final LoadSettlementSourceUseCase loadSettlementSourceUseCase;

    @Value("#{jobParameters['periodStart']}")
    private String periodStartParam;

    @Value("#{jobParameters['periodEnd']}")
    private String periodEndParam;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SettlementPeriod period = period();
        int loaded = loadSettlementSourceUseCase.load(period);
        log.info("정산 대상 라인 적재 스텝 완료. period={}, 신규적재={}", period, loaded);
        return RepeatStatus.FINISHED;
    }

    private SettlementPeriod period() {
        return SettlementPeriod.of(LocalDate.parse(periodStartParam), LocalDate.parse(periodEndParam));
    }
}
