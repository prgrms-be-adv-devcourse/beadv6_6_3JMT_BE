package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.event.RunEvent;
import com.prompthub.ai.settlement.domain.model.run.AgentRun;

import java.util.UUID;

public interface SettlementRunUseCase {

    AgentRun getOwnedRun(UUID actorId, UUID runId);

    boolean claimFirstStream(UUID actorId, UUID runId);

    void handleEvent(RunEvent event);
}
