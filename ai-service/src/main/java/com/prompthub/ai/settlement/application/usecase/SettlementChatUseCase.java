package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.model.conversation.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.model.run.RunStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SettlementChatUseCase {

    Optional<ConversationSnapshot> getCurrentConversation(UUID actorId);

    AcceptedRun acceptQuestion(UUID actorId, String content);

    void deleteCurrentConversation(UUID actorId);

    record AcceptedRun(
            UUID conversationId,
            UUID runId,
            RunStatus status,
            Instant startedAt,
            Instant deadlineAt
    ) {
    }
}
