package com.prompthub.ai.settlement.domain.conversation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatPairTest {

    @Test
    void requiresUserThenAssistant() {
        Instant now = Instant.parse("2026-07-22T12:00:00Z");
        ChatMessage assistant = ChatMessage.assistant("답변", now);

        assertThatThrownBy(() -> new ChatPair(assistant, assistant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER");
    }
}
