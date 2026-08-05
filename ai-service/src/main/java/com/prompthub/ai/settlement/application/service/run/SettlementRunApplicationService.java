package com.prompthub.ai.settlement.application.service.run;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.usecase.SettlementRunEventBroadcaster;
import com.prompthub.ai.settlement.application.usecase.SettlementRunUseCase;
import com.prompthub.ai.settlement.domain.event.RunEvent;
import com.prompthub.ai.settlement.domain.model.run.AgentRun;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SettlementRunApplicationService implements SettlementRunUseCase {

    private final SettlementChatStateRepository stateRepository;
    private final SettlementRunEventBroadcaster eventBroadcaster;
    private final SettlementRunTaskRegistry taskRegistry;
    private final AiSettlementProperties properties;
    private final Clock clock;

    public SettlementRunApplicationService(
            SettlementChatStateRepository stateRepository,
            SettlementRunEventBroadcaster eventBroadcaster,
            SettlementRunTaskRegistry taskRegistry,
            AiSettlementProperties properties,
            Clock clock
    ) {
        this.stateRepository = stateRepository;
        this.eventBroadcaster = eventBroadcaster;
        this.taskRegistry = taskRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AgentRun getOwnedRun(UUID actorId, UUID runId) {
        assertEnabled();
        return stateRepository.findOwnedRun(actorId, runId, clock.instant())
                .orElseThrow(() -> new AiException(AiErrorCode.AI_RUN_NOT_FOUND));
    }

    @Override
    public boolean claimFirstStream(UUID actorId, UUID runId) {
        assertEnabled();
        return stateRepository.claimFirstStream(actorId, runId);
    }

    @Override
    public void handleEvent(RunEvent event) {
        eventBroadcaster.broadcast(event);
        if (event.type() == RunEvent.RunEventType.CANCELLED) {
            taskRegistry.cancel(event.runId());
        }
    }

    private void assertEnabled() {
        if (!properties.settlement().chat().enabled()) {
            throw new AiException(AiErrorCode.AI_CHAT_DISABLED);
        }
    }
}
