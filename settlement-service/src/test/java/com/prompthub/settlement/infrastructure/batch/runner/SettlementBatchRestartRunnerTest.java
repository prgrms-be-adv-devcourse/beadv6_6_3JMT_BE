package com.prompthub.settlement.infrastructure.batch.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class SettlementBatchRestartRunnerTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 21, 10, 0);

    private RestartSettlementBatchUseCase useCase;
    private SettlementBatchRestartRunner runner;

    @BeforeEach
    void setUp() {
        useCase = mock(RestartSettlementBatchUseCase.class);
        runner = new SettlementBatchRestartRunner(useCase, BATCH_ID, ACTOR_ID);
    }

    @Test
    @DisplayName("재시작이 완료되면 배치와 관리자 ID를 전달하고 종료 코드 0을 반환한다")
    void run_completed_returnsZero() {
        given(useCase.restart(any())).willReturn(new SettlementJobResult(
                102L,
                "settlementJob",
                "COMPLETED",
                START_TIME));

        runner.run(mock(ApplicationArguments.class));

        then(useCase).should().restart(argThat(command ->
                command.batchId().equals(BATCH_ID) && command.actorId().equals(ACTOR_ID)));
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    @DisplayName("재시작 결과가 COMPLETED가 아니면 종료 코드 1을 반환한다")
    void run_failedResult_returnsOne() {
        given(useCase.restart(any())).willReturn(new SettlementJobResult(
                102L,
                "settlementJob",
                "FAILED",
                START_TIME));

        runner.run(mock(ApplicationArguments.class));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시작 유스케이스 예외가 발생하면 종료 코드 1을 반환한다")
    void run_useCaseThrows_returnsOne() {
        given(useCase.restart(any())).willThrow(new IllegalStateException("restart failure"));

        runner.run(mock(ApplicationArguments.class));

        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
