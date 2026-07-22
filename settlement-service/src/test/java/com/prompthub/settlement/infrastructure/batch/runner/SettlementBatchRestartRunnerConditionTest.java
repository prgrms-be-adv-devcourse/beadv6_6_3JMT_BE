package com.prompthub.settlement.infrastructure.batch.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SettlementBatchRestartRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    RestartSettlementBatchUseCase.class,
                    () -> mock(RestartSettlementBatchUseCase.class))
            .withUserConfiguration(SettlementBatchRestartRunner.class);

    @Test
    void restartModeCreatesRunner() {
        contextRunner
                .withPropertyValues(
                        "settlement.execution.mode=restart",
                        "settlement.restart.batch-id=00000000-0000-0000-0000-000000000701",
                        "settlement.restart.actor-id=00000000-0000-0000-0000-000000000702")
                .run(context -> assertThat(context)
                        .hasSingleBean(SettlementBatchRestartRunner.class));
    }

    @Test
    void defaultModeDoesNotCreateRunner() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(SettlementBatchRestartRunner.class));
    }
}
