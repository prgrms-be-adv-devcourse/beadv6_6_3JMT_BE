package com.prompthub.ai.settlement;

import com.prompthub.ai.global.config.AiSettlementProperties;

import java.time.Duration;

public final class AiSettlementTestFixtures {

    private AiSettlementTestFixtures() {
    }

    public static AiSettlementProperties properties(boolean enabled) {
        return new AiSettlementProperties(
                "gpt-5.6-luna",
                "low",
                2_000,
                8_000,
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                new AiSettlementProperties.Execution(4),
                new AiSettlementProperties.Conversation(Duration.ofHours(24), 20),
                new AiSettlementProperties.Sse(Duration.ofSeconds(15)),
                new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(enabled)),
                "internal-token"
        );
    }
}
