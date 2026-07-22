package com.prompthub.ai.settlement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChatMessage(
        UUID messageId,
        ChatRole role,
        String content,
        Instant createdAt
) {

    public ChatMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content는 비어 있을 수 없습니다.");
        }
        content = content.strip();
    }

    public static ChatMessage user(String content, Instant createdAt) {
        return new ChatMessage(UUID.randomUUID(), ChatRole.USER, content, createdAt);
    }

    public static ChatMessage assistant(String content, Instant createdAt) {
        return new ChatMessage(UUID.randomUUID(), ChatRole.ASSISTANT, content, createdAt);
    }
}
