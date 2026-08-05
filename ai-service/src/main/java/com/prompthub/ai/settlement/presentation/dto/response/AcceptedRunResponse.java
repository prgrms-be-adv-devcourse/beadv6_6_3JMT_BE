package com.prompthub.ai.settlement.presentation.dto.response;

import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.model.run.RunStatus;

import java.time.Instant;
import java.util.UUID;

public record AcceptedRunResponse(
        UUID conversationId,
        UUID runId,
        RunStatus status,
        Instant startedAt,
        Instant deadlineAt
) {

    public static AcceptedRunResponse from(SettlementChatUseCase.AcceptedRun acceptedRun) {
        return new AcceptedRunResponse(
                acceptedRun.conversationId(),
                acceptedRun.runId(),
                acceptedRun.status(),
                acceptedRun.startedAt(),
                acceptedRun.deadlineAt()
        );
    }
}
