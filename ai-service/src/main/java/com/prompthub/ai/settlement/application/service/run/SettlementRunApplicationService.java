package com.prompthub.ai.settlement.application.service.run;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.usecase.SettlementRunUseCase;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.run.AgentRun;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SettlementRunApplicationService implements SettlementRunUseCase {

    private final SettlementChatStateRepository stateRepository;
    private final AiSettlementProperties properties;
    private final Clock clock;

    public SettlementRunApplicationService(
            SettlementChatStateRepository stateRepository,
            AiSettlementProperties properties,
            Clock clock
    ) {
        this.stateRepository = stateRepository;
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

    private void assertEnabled() {
        if (!properties.settlement().chat().enabled()) {
            throw new AiException(AiErrorCode.AI_CHAT_DISABLED);
        }
    }
}
