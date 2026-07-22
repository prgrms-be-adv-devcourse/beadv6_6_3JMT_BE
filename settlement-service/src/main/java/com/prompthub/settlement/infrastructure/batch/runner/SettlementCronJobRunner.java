package com.prompthub.settlement.infrastructure.batch.runner;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.execution.mode", havingValue = "cronjob")
public class SettlementCronJobRunner implements ApplicationRunner, ExitCodeGenerator {

    private final RunSettlementBatchUseCase runSettlementBatchUseCase;
    private final Clock clock;
    private int exitCode = 1;

    @Override
    public void run(ApplicationArguments args) {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.now(clock));
        try {
            SettlementJobResult result = runSettlementBatchUseCase.run(
                    RunSettlementBatchCommand.scheduled(period));
            exitCode = "COMPLETED".equals(result.status()) ? 0 : 1;
            log.info("주간 정산 CronJob 종료. periodStart={}, periodEnd={}, jobExecutionId={}, status={}",
                    period.periodStart(), period.periodEnd(), result.jobExecutionId(), result.status());
        } catch (Exception exception) {
            exitCode = 1;
            log.error("주간 정산 CronJob 실패. periodStart={}, periodEnd={}",
                    period.periodStart(), period.periodEnd(), exception);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
