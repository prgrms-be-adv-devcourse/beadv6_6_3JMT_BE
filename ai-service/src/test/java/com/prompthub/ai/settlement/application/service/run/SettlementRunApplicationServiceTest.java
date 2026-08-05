package com.prompthub.ai.settlement.application.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.prompthub.ai.settlement.AiSettlementTestFixtures;
import com.prompthub.ai.settlement.application.usecase.SettlementRunEventBroadcaster;
import com.prompthub.ai.settlement.domain.event.RunEvent;
import com.prompthub.ai.settlement.domain.model.run.AgentRun;
import com.prompthub.ai.settlement.domain.model.run.RunStage;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
        SettlementRunApplicationService service = service(
                repository,
                mock(SettlementRunEventBroadcaster.class),
                mock(SettlementRunTaskRegistry.class));

        AgentRun result = service.getOwnedRun(actorId, run.runId());

        assertThat(result).isEqualTo(run);
    }

    @Test
    void handleEventBroadcastsNonCancelledEventWithoutCancellingTask() {
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventBroadcaster broadcaster = mock(SettlementRunEventBroadcaster.class);
        SettlementRunTaskRegistry taskRegistry = mock(SettlementRunTaskRegistry.class);
        SettlementRunApplicationService service = service(repository, broadcaster, taskRegistry);
        RunEvent event = RunEvent.progress(UUID.randomUUID(), RunStage.ANALYZING, NOW);

        service.handleEvent(event);

        verify(broadcaster).broadcast(event);
        verifyNoInteractions(taskRegistry);
    }

    @Test
    void handleEventBroadcastsCancelledEventBeforeCancellingLocalTask() {
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventBroadcaster broadcaster = mock(SettlementRunEventBroadcaster.class);
        SettlementRunTaskRegistry taskRegistry = mock(SettlementRunTaskRegistry.class);
        SettlementRunApplicationService service = service(repository, broadcaster, taskRegistry);
        RunEvent event = RunEvent.cancelled(UUID.randomUUID(), NOW);

        service.handleEvent(event);

        InOrder order = inOrder(broadcaster, taskRegistry);
        order.verify(broadcaster).broadcast(event);
        order.verify(taskRegistry).cancel(event.runId());
    }

    private SettlementRunApplicationService service(
            SettlementChatStateRepository repository,
            SettlementRunEventBroadcaster broadcaster,
            SettlementRunTaskRegistry taskRegistry
    ) {
        return new SettlementRunApplicationService(
                repository,
                broadcaster,
                taskRegistry,
                AiSettlementTestFixtures.properties(true),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }
}
