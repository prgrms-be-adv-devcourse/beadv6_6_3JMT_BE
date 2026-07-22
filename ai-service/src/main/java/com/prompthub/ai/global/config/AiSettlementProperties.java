package com.prompthub.ai.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "ai")
public record AiSettlementProperties(
        @DefaultValue("gpt-5.6-luna") @NotBlank String model,
        @DefaultValue("low") @NotBlank String reasoningEffort,
        @DefaultValue("2000") @Min(1) int maxCompletionTokens,
        @DefaultValue("8000") @Min(1) int historyMaxTokens,
        @DefaultValue("90s") @NotNull Duration runTimeout,
        @DefaultValue("3s") @NotNull Duration userGrpcDeadline,
        @DefaultValue @Valid Execution execution,
        @DefaultValue @Valid Conversation conversation,
        @DefaultValue @Valid Sse sse,
        @DefaultValue @Valid Settlement settlement,
        @NotBlank String userGrpcInternalToken
) {

    private static final Set<String> SUPPORTED_REASONING_EFFORTS =
            Set.of("none", "low", "medium", "high", "xhigh", "max");

    public AiSettlementProperties {
        requirePositive(runTimeout, "runTimeout");
        requirePositive(userGrpcDeadline, "userGrpcDeadline");
        if (reasoningEffort != null && !SUPPORTED_REASONING_EFFORTS.contains(reasoningEffort)) {
            throw new IllegalArgumentException("지원하지 않는 reasoning effort입니다.");
        }
    }

    public record Execution(
            @DefaultValue("4") @Min(1) int maxConcurrentRuns
    ) {
    }

    public record Conversation(
            @DefaultValue("24h") @NotNull Duration ttl,
            @DefaultValue("20") @Min(1) int maxPairs
    ) {
        public Conversation {
            requirePositive(ttl, "conversation.ttl");
        }
    }

    public record Sse(
            @DefaultValue("15s") @NotNull Duration heartbeat
    ) {
        public Sse {
            requirePositive(heartbeat, "heartbeat");
        }
    }

    public record Settlement(
            @DefaultValue @Valid Chat chat
    ) {
    }

    public record Chat(
            @DefaultValue("false") boolean enabled
    ) {
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(property + "은 양수여야 합니다.");
        }
    }
}
