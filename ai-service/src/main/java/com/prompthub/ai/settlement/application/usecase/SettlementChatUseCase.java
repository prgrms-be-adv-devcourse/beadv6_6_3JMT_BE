package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.AgentRun;
import com.prompthub.ai.settlement.domain.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.RunStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SettlementChatUseCase {

    Optional<ConversationSnapshot> getCurrentConversation(UUID actorId);

    AcceptedRun acceptQuestion(UUID actorId, String content);

    void deleteCurrentConversation(UUID actorId);

    AgentRun getOwnedRun(UUID actorId, UUID runId);

    boolean claimFirstStream(UUID actorId, UUID runId);

    record AcceptedRun(
            UUID conversationId,
            UUID runId,
            RunStatus status,
            Instant startedAt,
            Instant deadlineAt
    ) {
    }
}
