package com.prompthub.ai.settlement.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ConversationSnapshot(
        UUID conversationId,
        List<ChatPair> pairs,
        AgentRun latestRun,
        UUID activeRunId,
        Instant expiresAt
) {

    public ConversationSnapshot {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(pairs, "pairs");
        Objects.requireNonNull(latestRun, "latestRun");
        Objects.requireNonNull(expiresAt, "expiresAt");
        pairs = List.copyOf(pairs);
    }
}
