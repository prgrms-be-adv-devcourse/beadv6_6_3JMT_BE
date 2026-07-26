package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.run.AgentRun;

import java.util.UUID;

public interface SettlementRunUseCase {

    AgentRun getOwnedRun(UUID actorId, UUID runId);

    boolean claimFirstStream(UUID actorId, UUID runId);
}
