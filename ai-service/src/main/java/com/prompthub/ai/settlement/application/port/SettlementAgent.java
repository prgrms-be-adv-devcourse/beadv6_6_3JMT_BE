package com.prompthub.ai.settlement.application.port;

import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.run.RunStage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface SettlementAgent {

    AgentResult answer(AgentRequest request);

    record AgentRequest(
            UUID actorId,
            UUID runId,
            String question,
            List<ChatPair> completedHistory,
            Instant deadlineAt,
            ProgressListener progressListener
    ) {
        public AgentRequest {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question은 비어 있을 수 없습니다.");
            }
            question = question.strip();
            completedHistory = List.copyOf(Objects.requireNonNull(completedHistory, "completedHistory"));
            Objects.requireNonNull(progressListener, "progressListener");
        }
    }

    @FunctionalInterface
    interface ProgressListener {
        void onStage(RunStage stage);
    }

    record AgentResult(
            String answer,
            List<String> chunks,
            int toolRounds
    ) {
        public AgentResult {
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("answer는 비어 있을 수 없습니다.");
            }
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
            if (!answer.equals(String.join("", chunks))) {
                throw new IllegalArgumentException("chunks는 answer와 일치해야 합니다.");
            }
            if (toolRounds < 0 || toolRounds > 4) {
                throw new IllegalArgumentException("toolRounds는 0 이상 4 이하여야 합니다.");
            }
        }
    }
}
