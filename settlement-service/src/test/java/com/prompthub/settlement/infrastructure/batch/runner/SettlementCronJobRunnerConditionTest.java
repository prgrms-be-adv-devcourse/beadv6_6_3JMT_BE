package com.prompthub.settlement.infrastructure.batch.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SettlementCronJobRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RunSettlementBatchUseCase.class,
                    () -> mock(RunSettlementBatchUseCase.class))
            .withBean(Clock.class, () -> Clock.fixed(
                    Instant.parse("2026-07-19T15:00:00Z"),
                    ZoneId.of("Asia/Seoul")))
            .withUserConfiguration(SettlementCronJobRunner.class);

    @Test
    void cronjobModeCreatesRunner() {
        contextRunner
                .withPropertyValues("settlement.execution.mode=cronjob")
                .run(context -> assertThat(context)
                        .hasSingleBean(SettlementCronJobRunner.class));
    }

    @Test
    void defaultModeDoesNotCreateRunner() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(SettlementCronJobRunner.class));
    }
}
