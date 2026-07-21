package com.prompthub.settlement.infrastructure.batch.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class SettlementCronJobRunnerTest {

    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 20, 0, 0);

    private RunSettlementBatchUseCase useCase;
    private SettlementCronJobRunner runner;

    @BeforeEach
    void setUp() {
        useCase = mock(RunSettlementBatchUseCase.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-19T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        runner = new SettlementCronJobRunner(useCase, clock);
    }

    @Test
    void run_onMondayExecutesPreviousWeekAndReturnsZeroWhenCompleted() throws Exception {
        given(useCase.run(any())).willReturn(
                new SettlementJobResult(10L, "settlementJob", "COMPLETED", START_TIME));

        runner.run(mock(ApplicationArguments.class));

        then(useCase).should().run(argThat(command ->
                command.triggerType() == TriggerType.SCHEDULED
                        && command.period().equals(SettlementPeriod.of(
                                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)))));
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void run_returnsOneWhenBatchDoesNotComplete() throws Exception {
        given(useCase.run(any())).willReturn(
                new SettlementJobResult(11L, "settlementJob", "FAILED", START_TIME));

        runner.run(mock(ApplicationArguments.class));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void run_returnsOneWhenUseCaseThrows() throws Exception {
        given(useCase.run(any())).willThrow(new IllegalStateException("order unavailable"));

        runner.run(mock(ApplicationArguments.class));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
