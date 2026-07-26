package com.prompthub.ai.settlement.domain.repository;

import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.conversation.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import com.prompthub.ai.settlement.domain.run.RunStage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface SettlementChatStateRepository {

    AcceptRunResult acceptRun(UUID actorId, UUID proposedConversationId, AgentRun run);

    Optional<ConversationSnapshot> findCurrentConversation(UUID actorId, Instant now);

    Optional<AgentRun> findOwnedRun(UUID actorId, UUID runId, Instant now);

    boolean updateStage(UUID runId, RunStage stage, Instant occurredAt);

    boolean complete(UUID actorId, UUID runId, ChatPair pair, String answer, Instant completedAt);

    boolean fail(UUID actorId, UUID runId, String code, String message, Instant failedAt);

    Optional<ConversationCancellation> markCurrentRunCancelled(UUID actorId, Instant cancelledAt);

    boolean cleanupCancelledConversation(UUID actorId, ConversationCancellation cancellation);

    boolean expireStaleRun(UUID actorId, UUID runId, Instant now);

    boolean claimFirstStream(UUID actorId, UUID runId);

    record ConversationCancellation(
            UUID conversationId,
            Optional<UUID> cancelledRunId
    ) {

        public ConversationCancellation {
            Objects.requireNonNull(conversationId, "conversationId는 필수입니다.");
            cancelledRunId = cancelledRunId == null ? Optional.empty() : cancelledRunId;
        }
    }

    record AcceptRunResult(
            boolean accepted,
            UUID conversationId,
            Optional<UUID> activeRunId
    ) {

        public AcceptRunResult {
            activeRunId = activeRunId == null ? Optional.empty() : activeRunId;
            if (accepted && (conversationId == null || activeRunId.isPresent())) {
                throw new IllegalArgumentException("accepted 결과에는 conversationId만 필요합니다.");
            }
            if (!accepted && (conversationId != null || activeRunId.isEmpty())) {
                throw new IllegalArgumentException("run-in-progress 결과에는 activeRunId만 필요합니다.");
            }
        }

        public static AcceptRunResult accepted(UUID conversationId) {
            return new AcceptRunResult(true, conversationId, Optional.empty());
        }

        public static AcceptRunResult runInProgress(UUID activeRunId) {
            return new AcceptRunResult(false, null, Optional.of(activeRunId));
        }
    }
}
