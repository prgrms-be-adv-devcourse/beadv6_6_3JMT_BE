package com.prompthub.ai.settlement.domain;

import java.util.Objects;

public record ChatPair(
        ChatMessage user,
        ChatMessage assistant
) {

    public ChatPair {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(assistant, "assistant");
        if (user.role() != ChatRole.USER) {
            throw new IllegalArgumentException("user message의 role은 USER여야 합니다.");
        }
        if (assistant.role() != ChatRole.ASSISTANT) {
            throw new IllegalArgumentException("assistant message의 role은 ASSISTANT여야 합니다.");
        }
        if (assistant.createdAt().isBefore(user.createdAt())) {
            throw new IllegalArgumentException("assistant message는 user message보다 먼저 생성될 수 없습니다.");
        }
    }
}
