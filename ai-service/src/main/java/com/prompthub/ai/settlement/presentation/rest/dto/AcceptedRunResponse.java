package com.prompthub.ai.settlement.presentation.rest.dto;

import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.RunStatus;

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
