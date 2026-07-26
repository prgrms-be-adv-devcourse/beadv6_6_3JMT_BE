package com.prompthub.ai.settlement.infrastructure.client.openai;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import com.prompthub.ai.settlement.domain.run.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutionGuard {

    public static final String ACTOR_ID = "actorId";
    public static final String RUN_ID = "runId";

    private final SettlementChatStateRepository stateRepository;
    private final Clock clock;

    public ToolExecutionGuard(SettlementChatStateRepository stateRepository, Clock clock) {
        this.stateRepository = stateRepository;
        this.clock = clock;
    }

    public RunningToolContext assertRunning(ToolContext toolContext) {
        RunningToolContext context = parse(toolContext);
        Instant now = clock.instant();
        AgentRun run = stateRepository.findOwnedRun(context.actorId(), context.runId(), now)
                .orElseThrow(() -> new AiException(AiErrorCode.AI_RUN_NOT_FOUND));
        if (run.status() != RunStatus.RUNNING) {
            throw new AiException(run.errorCode() != null
                    && AiErrorCode.RUN_TIMEOUT.getCode().equals(run.errorCode())
                    ? AiErrorCode.RUN_TIMEOUT
                    : AiErrorCode.AI_RUN_NOT_FOUND);
        }
        if (!now.isBefore(run.deadlineAt())) {
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        }
        return context;
    }

    private RunningToolContext parse(ToolContext toolContext) {
        try {
            Map<String, Object> context = toolContext.getContext();
            return new RunningToolContext(
                    UUID.fromString((String) context.get(ACTOR_ID)),
                    UUID.fromString((String) context.get(RUN_ID)));
        } catch (RuntimeException exception) {
            throw new AiException(AiErrorCode.AI_INTERNAL_ERROR);
        }
    }

    public record RunningToolContext(UUID actorId, UUID runId) {
    }
}
