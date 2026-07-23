package com.prompthub.ai.settlement.application.service;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.RunEventPublisher;
import com.prompthub.ai.settlement.application.port.SettlementAgent;
import com.prompthub.ai.settlement.application.port.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.conversation.ChatMessage;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import com.prompthub.ai.settlement.domain.run.RunStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class SettlementRunExecutor {

    private static final String NO_ERROR = "none";

    private final SettlementChatStateRepository stateRepository;
    private final SettlementAgent settlementAgent;
    private final RunEventPublisher eventPublisher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public SettlementRunExecutor(
            SettlementChatStateRepository stateRepository,
            SettlementAgent settlementAgent,
            RunEventPublisher eventPublisher,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.stateRepository = stateRepository;
        this.settlementAgent = settlementAgent;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public void execute(AgentRun run, List<ChatPair> completedHistory) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            advanceStage(run, RunStage.ANALYZING);
            SettlementAgent.AgentResult result = settlementAgent.answer(new SettlementAgent.AgentRequest(
                    run.actorId(),
                    run.runId(),
                    run.question(),
                    completedHistory,
                    run.deadlineAt(),
                    stage -> advanceStage(run, stage)
            ));
            Instant completedAt = clock.instant();
            if (completedAt.isAfter(run.deadlineAt())) {
                throw new AiException(AiErrorCode.RUN_TIMEOUT);
            }
            ChatPair pair = new ChatPair(
                    ChatMessage.user(run.question(), run.startedAt()),
                    ChatMessage.assistant(result.answer(), completedAt)
            );
            if (!stateRepository.complete(
                    run.actorId(), run.runId(), pair, result.answer(), completedAt)) {
                log.info("AI settlement completion fenced. runId={}", run.runId());
                return;
            }

            recordTerminal(sample, "COMPLETED", NO_ERROR);
            publishCompleted(run, result, completedAt);
        } catch (RunFencedException exception) {
            log.info("AI settlement run fenced before terminal commit. runId={}", run.runId());
        } catch (RuntimeException exception) {
            failSafely(run, errorCode(exception), clock.instant(), sample);
        }
    }

    private void advanceStage(AgentRun run, RunStage stage) {
        Instant occurredAt = clock.instant();
        if (occurredAt.isAfter(run.deadlineAt())) {
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        }
        if (!stateRepository.updateStage(run.runId(), stage, occurredAt)) {
            throw new RunFencedException();
        }
        eventPublisher.progress(run.runId(), stage, occurredAt);
    }

    private void publishCompleted(
            AgentRun run,
            SettlementAgent.AgentResult result,
            Instant completedAt
    ) {
        try {
            long sequence = 1L;
            for (String chunk : result.chunks()) {
                eventPublisher.delta(run.runId(), sequence++, chunk, completedAt);
            }
            eventPublisher.done(run.runId(), result.answer(), completedAt);
        } catch (RuntimeException exception) {
            log.warn("AI settlement terminal event publish failed. runId={}, category={}",
                    run.runId(), exception.getClass().getSimpleName());
        }
    }

    private void failSafely(
            AgentRun run,
            AiErrorCode errorCode,
            Instant failedAt,
            Timer.Sample sample
    ) {
        try {
            if (!stateRepository.fail(
                    run.actorId(),
                    run.runId(),
                    errorCode.getCode(),
                    errorCode.getMessage(),
                    failedAt)) {
                log.info("AI settlement failure fenced. runId={}, errorCode={}",
                        run.runId(), errorCode.getCode());
                return;
            }
            recordTerminal(sample, "FAILED", errorCode.getCode());
            try {
                eventPublisher.failed(
                        run.runId(),
                        errorCode.getCode(),
                        errorCode.getMessage(),
                        failedAt);
            } catch (RuntimeException publishException) {
                log.warn("AI settlement failed event publish failed. runId={}, category={}",
                        run.runId(), publishException.getClass().getSimpleName());
            }
        } catch (RuntimeException stateException) {
            log.warn("AI settlement failure commit unavailable. runId={}, category={}",
                    run.runId(), stateException.getClass().getSimpleName());
        }
    }

    private AiErrorCode errorCode(Throwable throwable) {
        if (Thread.currentThread().isInterrupted()) {
            return AiErrorCode.RUN_TIMEOUT;
        }
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AiException aiException
                    && aiException.getErrorCode() instanceof AiErrorCode aiErrorCode) {
                return aiErrorCode;
            }
            current = current.getCause();
        }
        return AiErrorCode.AI_INTERNAL_ERROR;
    }

    private void recordTerminal(Timer.Sample sample, String status, String errorCode) {
        meterRegistry.counter(
                "ai.chat.runs",
                "status", status,
                "error_code", errorCode).increment();
        sample.stop(meterRegistry.timer(
                "ai.chat.run.duration",
                "status", status,
                "error_code", errorCode));
    }

    private static final class RunFencedException extends RuntimeException {
    }
}
