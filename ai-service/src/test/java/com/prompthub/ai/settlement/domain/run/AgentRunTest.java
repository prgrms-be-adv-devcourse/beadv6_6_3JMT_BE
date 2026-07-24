package com.prompthub.ai.settlement.domain.run;

import com.prompthub.ai.settlement.domain.exception.InvalidRunStateException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTest {

    @Test
    void startsWithUuidV4AndAllowsOnlyOneTerminalTransition() {
        Instant startedAt = Instant.parse("2026-07-22T12:00:00Z");
        AgentRun run = AgentRun.start(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "  7월 정산을 알려줘  ",
                startedAt,
                Duration.ofSeconds(90)
        );

        assertThat(run.runId().version()).isEqualTo(4);
        assertThat(run.question()).isEqualTo("7월 정산을 알려줘");
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.stage()).isEqualTo(RunStage.ANALYZING);
        assertThat(run.deadlineAt()).isEqualTo(startedAt.plusSeconds(90));

        AgentRun completed = run.complete("정산 답변", startedAt.plusSeconds(10));
        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.stage()).isEqualTo(RunStage.DONE);

        assertThatThrownBy(() -> completed.fail("AI_PROVIDER_UNAVAILABLE", "안전한 오류", startedAt.plusSeconds(11)))
                .isInstanceOf(InvalidRunStateException.class);
    }
}
