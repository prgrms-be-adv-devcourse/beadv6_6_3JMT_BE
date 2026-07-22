package com.prompthub.ai.settlement.presentation.dto;

import com.prompthub.ai.settlement.domain.AgentRun;
import com.prompthub.ai.settlement.domain.ChatMessage;
import com.prompthub.ai.settlement.domain.ChatRole;
import com.prompthub.ai.settlement.domain.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.RunStage;
import com.prompthub.ai.settlement.domain.RunStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID conversationId,
        List<MessageResponse> messages,
        LatestRunResponse latestRun,
        UUID activeRunId,
        Instant expiresAt
) {

    public static ConversationResponse from(ConversationSnapshot snapshot) {
        List<MessageResponse> messages = new ArrayList<>();
        snapshot.pairs().forEach(pair -> {
            messages.add(MessageResponse.from(pair.user()));
            messages.add(MessageResponse.from(pair.assistant()));
        });
        return new ConversationResponse(
                snapshot.conversationId(),
                List.copyOf(messages),
                LatestRunResponse.from(snapshot.latestRun()),
                snapshot.activeRunId(),
                snapshot.expiresAt()
        );
    }

    public record MessageResponse(
            UUID messageId,
            ChatRole role,
            String content,
            Instant createdAt
    ) {

        private static MessageResponse from(ChatMessage message) {
            return new MessageResponse(
                    message.messageId(),
                    message.role(),
                    message.content(),
                    message.createdAt()
            );
        }
    }

    public record LatestRunResponse(
            UUID runId,
            String question,
            RunStatus status,
            RunStage stage,
            Instant startedAt,
            Instant deadlineAt
    ) {

        private static LatestRunResponse from(AgentRun run) {
            return new LatestRunResponse(
                    run.runId(),
                    run.question(),
                    run.status(),
                    run.stage(),
                    run.startedAt(),
                    run.deadlineAt()
            );
        }
    }
}
