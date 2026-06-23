package com.prompthub.settlement.config;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SettlementSchedulerConfig {

    private final RunSettlementBatchUseCase runSettlementBatchUseCase;

    @Scheduled(cron = "${settlement.scheduler.cron:0 0 4 1 * *}")
    public void runMonthlySettlement() {
        YearMonth period = YearMonth.now().minusMonths(1);
        try {
            runSettlementBatchUseCase.run(RunSettlementJobCommand.scheduled(period));
        } catch (Exception e) {
            log.error("자동정산 스케줄 실행 실패 - period={}", period, e);
        }
    }
}
