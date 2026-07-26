package com.prompthub.ai.settlement.application.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.settlement.AiSettlementTestFixtures;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementRunApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void getOwnedRunReturnsActorOwnedRun() {
        UUID actorId = UUID.randomUUID();
        AgentRun run = AgentRun.start(
                UUID.randomUUID(),
                actorId,
                "이번 달 정산 요약",
                NOW,
                Duration.ofSeconds(90));
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        given(repository.findOwnedRun(actorId, run.runId(), NOW))
                .willReturn(Optional.of(run));
        SettlementRunApplicationService service = new SettlementRunApplicationService(
                repository,
                AiSettlementTestFixtures.properties(true),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

        AgentRun result = service.getOwnedRun(actorId, run.runId());

        assertThat(result).isEqualTo(run);
    }
}
