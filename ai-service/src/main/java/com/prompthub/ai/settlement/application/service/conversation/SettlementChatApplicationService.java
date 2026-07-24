package com.prompthub.ai.settlement.application.service.conversation;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SettlementRunEventPublisher;
import com.prompthub.ai.settlement.application.service.run.SettlementRunConcurrencyLimiter;
import com.prompthub.ai.settlement.application.service.run.SettlementRunExecutor;
import com.prompthub.ai.settlement.application.service.run.SettlementRunTaskRegistry;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.conversation.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class SettlementChatApplicationService implements SettlementChatUseCase {

    private static final int MAX_QUESTION_LENGTH = 2_000;

    private final SettlementChatStateRepository stateRepository;
    private final SettlementRunExecutor runExecutor;
    private final SettlementRunEventPublisher eventPublisher;
    private final SettlementRunConcurrencyLimiter concurrencyLimiter;
    private final SettlementRunTaskRegistry taskRegistry;
    private final Executor executor;
    private final AiSettlementProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public SettlementChatApplicationService(
            SettlementChatStateRepository stateRepository,
            SettlementRunExecutor runExecutor,
            SettlementRunEventPublisher eventPublisher,
            SettlementRunConcurrencyLimiter concurrencyLimiter,
            SettlementRunTaskRegistry taskRegistry,
            @Qualifier("aiSettlementExecutor") Executor executor,
            AiSettlementProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.stateRepository = stateRepository;
        this.runExecutor = runExecutor;
        this.eventPublisher = eventPublisher;
        this.concurrencyLimiter = concurrencyLimiter;
        this.taskRegistry = taskRegistry;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<ConversationSnapshot> getCurrentConversation(UUID actorId) {
        assertEnabled();
        return stateRepository.findCurrentConversation(actorId, clock.instant());
    }

    @Override
    public AcceptedRun acceptQuestion(UUID actorId, String content) {
        assertEnabled();
        String question = validateQuestion(content);
        Instant startedAt = clock.instant();
        Optional<ConversationSnapshot> current = stateRepository.findCurrentConversation(actorId, startedAt);
        if (current.map(ConversationSnapshot::activeRunId).orElse(null) != null) {
            throw new AiException(AiErrorCode.RUN_IN_PROGRESS);
        }

        SettlementRunConcurrencyLimiter.Lease lease = concurrencyLimiter.tryAcquire()
                .orElseThrow(() -> new AiException(AiErrorCode.AI_CAPACITY_EXCEEDED));
        UUID proposedConversationId = current
                .map(ConversationSnapshot::conversationId)
                .orElseGet(UUID::randomUUID);
        AgentRun run = AgentRun.start(
                proposedConversationId,
                actorId,
                question,
                startedAt,
                properties.runTimeout()
        );
        SettlementChatStateRepository.AcceptRunResult accepted;
        try {
            accepted = stateRepository.acceptRun(actorId, proposedConversationId, run);
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        if (!accepted.accepted()) {
            lease.close();
            throw new AiException(AiErrorCode.RUN_IN_PROGRESS);
        }

        List<ChatPair> completedHistory = current
                .filter(snapshot -> snapshot.conversationId().equals(accepted.conversationId()))
                .map(ConversationSnapshot::pairs)
                .orElseGet(List::of);
        FutureTask<Void> future;
        try {
            future = taskRegistry.register(
                    run.runId(),
                    () -> runExecutor.execute(run, completedHistory),
                    lease::close
            );
        } catch (RuntimeException exception) {
            lease.close();
            failAcceptedRun(run, AiErrorCode.AI_INTERNAL_ERROR, startedAt);
            throw new AiException(AiErrorCode.AI_INTERNAL_ERROR);
        }

        try {
            executor.execute(future);
        } catch (RejectedExecutionException exception) {
            failAcceptedRun(run, AiErrorCode.AI_CAPACITY_EXCEEDED, clock.instant());
            future.cancel(false);
            throw new AiException(AiErrorCode.AI_CAPACITY_EXCEEDED);
        } catch (RuntimeException exception) {
            failAcceptedRun(run, AiErrorCode.AI_INTERNAL_ERROR, clock.instant());
            future.cancel(false);
            throw new AiException(AiErrorCode.AI_INTERNAL_ERROR);
        }

        return new AcceptedRun(
                accepted.conversationId(),
                run.runId(),
                run.status(),
                run.startedAt(),
                run.deadlineAt()
        );
    }

    @Override
    public void deleteCurrentConversation(UUID actorId) {
        assertEnabled();
        Instant cancelledAt = clock.instant();
        Optional<UUID> cancelledRunId = stateRepository.cancelCurrentConversation(actorId, cancelledAt);
        if (cancelledRunId.isEmpty()) {
            return;
        }
        UUID runId = cancelledRunId.orElseThrow();
        meterRegistry.counter(
                "ai.chat.runs",
                "status", "CANCELLED",
                "error_code", "none").increment();
        try {
            try {
                eventPublisher.cancelled(runId, cancelledAt);
            } catch (RuntimeException publishException) {
                log.warn("AI settlement cancelled event publish failed. runId={}, category={}",
                        runId, publishException.getClass().getSimpleName());
            }
        } finally {
            taskRegistry.cancel(runId);
        }
    }

    private void failAcceptedRun(AgentRun run, AiErrorCode errorCode, Instant failedAt) {
        try {
            if (!stateRepository.fail(
                    run.actorId(),
                    run.runId(),
                    errorCode.getCode(),
                    errorCode.getMessage(),
                    failedAt)) {
                return;
            }
            meterRegistry.counter(
                    "ai.chat.runs",
                    "status", "FAILED",
                    "error_code", errorCode.getCode()).increment();
            try {
                eventPublisher.failed(
                        run.runId(),
                        errorCode.getCode(),
                        errorCode.getMessage(),
                        failedAt);
            } catch (RuntimeException publishException) {
                log.warn("AI settlement rejected run event publish failed. runId={}, category={}",
                        run.runId(), publishException.getClass().getSimpleName());
            }
        } catch (RuntimeException stateException) {
            log.warn("AI settlement rejected run failure commit unavailable. runId={}, category={}",
                    run.runId(), stateException.getClass().getSimpleName());
        }
    }

    private String validateQuestion(String content) {
        if (content == null) {
            throw new AiException(AiErrorCode.INVALID_CHAT_MESSAGE);
        }
        String question = content.strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            throw new AiException(AiErrorCode.INVALID_CHAT_MESSAGE);
        }
        return question;
    }

    private void assertEnabled() {
        if (!properties.settlement().chat().enabled()) {
            throw new AiException(AiErrorCode.AI_CHAT_DISABLED);
        }
    }
}
