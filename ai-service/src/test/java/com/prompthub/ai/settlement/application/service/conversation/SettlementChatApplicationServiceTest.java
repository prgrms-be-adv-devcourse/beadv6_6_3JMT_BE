package com.prompthub.ai.settlement.application.service.conversation;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SettlementRunEventPublisher;
import com.prompthub.ai.settlement.application.port.SettlementAgent;
import com.prompthub.ai.settlement.application.service.run.SettlementRunConcurrencyLimiter;
import com.prompthub.ai.settlement.application.service.run.SettlementRunExecutor;
import com.prompthub.ai.settlement.application.service.run.SettlementRunTaskRegistry;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository.ConversationCancellation;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import com.prompthub.ai.settlement.domain.run.RunStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementChatApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabledFeatureStopsBeforeStateAndCapacity() {
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunExecutor runExecutor = mock(SettlementRunExecutor.class);
        SettlementRunEventPublisher publisher = mock(SettlementRunEventPublisher.class);
        SettlementRunConcurrencyLimiter concurrencyLimiter =
                new SettlementRunConcurrencyLimiter(properties(true, 1));
        SettlementRunTaskRegistry taskRegistry = new SettlementRunTaskRegistry(new SimpleMeterRegistry());
        SettlementChatApplicationService service = service(
                repository,
                runExecutor,
                publisher,
                concurrencyLimiter,
                taskRegistry,
                Runnable::run,
                false
        );

        assertThatThrownBy(() -> service.acceptQuestion(UUID.randomUUID(), "7월 정산 요약"))
                .isInstanceOfSatisfying(AiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_CHAT_DISABLED));

        verifyNoInteractions(repository, runExecutor, publisher);
        assertThat(concurrencyLimiter.tryAcquire()).isPresent();
    }

    @Test
    void exhaustedCapacityDoesNotCreateRedisRun() {
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunConcurrencyLimiter concurrencyLimiter =
                new SettlementRunConcurrencyLimiter(properties(true, 1));
        SettlementRunConcurrencyLimiter.Lease occupied = concurrencyLimiter.tryAcquire().orElseThrow();
        SettlementChatApplicationService service = service(
                repository,
                mock(SettlementRunExecutor.class),
                mock(SettlementRunEventPublisher.class),
                concurrencyLimiter,
                new SettlementRunTaskRegistry(new SimpleMeterRegistry()),
                Runnable::run,
                true
        );
        when(repository.findCurrentConversation(any(), eq(NOW))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptQuestion(UUID.randomUUID(), "지난달 정산 요약"))
                .isInstanceOfSatisfying(AiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_CAPACITY_EXCEEDED));

        verify(repository, never()).acceptRun(any(), any(), any());
        occupied.close();
    }

    @Test
    void registersFutureBeforeSubmitAndDeleteCancelsInRequiredOrder() {
        UUID actorId = UUID.randomUUID();
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventPublisher publisher = mock(SettlementRunEventPublisher.class);
        SettlementRunConcurrencyLimiter concurrencyLimiter =
                new SettlementRunConcurrencyLimiter(properties(true, 1));
        SettlementRunTaskRegistry taskRegistry = spy(new SettlementRunTaskRegistry(new SimpleMeterRegistry()));
        AtomicBoolean registeredBeforeSubmit = new AtomicBoolean();
        Executor holdingExecutor = command -> registeredBeforeSubmit.set(taskRegistry.size() == 1);
        SettlementChatApplicationService service = service(
                repository,
                mock(SettlementRunExecutor.class),
                publisher,
                concurrencyLimiter,
                taskRegistry,
                holdingExecutor,
                true
        );
        when(repository.findCurrentConversation(actorId, NOW)).thenReturn(Optional.empty());
        when(repository.acceptRun(eq(actorId), any(), any())).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(2);
            return SettlementChatStateRepository.AcceptRunResult.accepted(run.conversationId());
        });

        SettlementChatUseCase.AcceptedRun accepted = service.acceptQuestion(actorId, " 이번 달 정산 요약 ");
        ConversationCancellation cancellation = new ConversationCancellation(
                accepted.conversationId(),
                Optional.of(accepted.runId())
        );
        when(repository.markCurrentRunCancelled(actorId, NOW))
                .thenReturn(Optional.of(cancellation));
        when(repository.cleanupCancelledConversation(actorId, cancellation)).thenReturn(true);
        doThrow(new AiException(AiErrorCode.AI_STATE_UNAVAILABLE))
                .when(publisher).cancelled(accepted.runId(), NOW);

        assertThatCode(() -> service.deleteCurrentConversation(actorId)).doesNotThrowAnyException();

        assertThat(registeredBeforeSubmit).isTrue();
        assertThat(accepted.status().name()).isEqualTo("RUNNING");
        assertThat(accepted.deadlineAt()).isEqualTo(NOW.plusSeconds(90));
        InOrder order = inOrder(repository, publisher, taskRegistry);
        order.verify(repository).markCurrentRunCancelled(actorId, NOW);
        order.verify(publisher).cancelled(accepted.runId(), NOW);
        order.verify(taskRegistry).cancel(accepted.runId());
        order.verify(repository).cleanupCancelledConversation(actorId, cancellation);
        assertThat(taskRegistry.size()).isZero();
        assertThat(concurrencyLimiter.tryAcquire()).isPresent();
    }

    @Test
    void rejectedSubmissionFailsAcceptedRunAndReleasesLease() {
        UUID actorId = UUID.randomUUID();
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventPublisher publisher = mock(SettlementRunEventPublisher.class);
        SettlementRunConcurrencyLimiter concurrencyLimiter =
                new SettlementRunConcurrencyLimiter(properties(true, 1));
        SettlementRunTaskRegistry taskRegistry = new SettlementRunTaskRegistry(new SimpleMeterRegistry());
        Executor rejectingExecutor = command -> {
            assertThat(taskRegistry.size()).isEqualTo(1);
            throw new RejectedExecutionException("full");
        };
        SettlementChatApplicationService service = service(
                repository,
                mock(SettlementRunExecutor.class),
                publisher,
                concurrencyLimiter,
                taskRegistry,
                rejectingExecutor,
                true
        );
        when(repository.findCurrentConversation(actorId, NOW)).thenReturn(Optional.empty());
        when(repository.acceptRun(eq(actorId), any(), any())).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(2);
            return SettlementChatStateRepository.AcceptRunResult.accepted(run.conversationId());
        });
        when(repository.fail(eq(actorId), any(), eq(AiErrorCode.AI_CAPACITY_EXCEEDED.getCode()),
                eq(AiErrorCode.AI_CAPACITY_EXCEEDED.getMessage()), eq(NOW))).thenReturn(true);

        assertThatThrownBy(() -> service.acceptQuestion(actorId, "정산 요약"))
                .isInstanceOfSatisfying(AiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_CAPACITY_EXCEEDED));

        verify(publisher).failed(any(), eq(AiErrorCode.AI_CAPACITY_EXCEEDED.getCode()),
                eq(AiErrorCode.AI_CAPACITY_EXCEEDED.getMessage()), eq(NOW));
        assertThat(taskRegistry.size()).isZero();
        assertThat(concurrencyLimiter.tryAcquire()).isPresent();
    }

    @Test
    void terminalCommitPrecedesEventsAndPublishFailureDoesNotOverwriteCompletion() {
        UUID actorId = UUID.randomUUID();
        AgentRun run = AgentRun.start(UUID.randomUUID(), actorId, "정산 요약", NOW, Duration.ofSeconds(90));
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventPublisher publisher = mock(SettlementRunEventPublisher.class);
        SettlementAgent agent = request -> {
            request.progressListener().onStage(RunStage.FETCHING_DATA);
            request.progressListener().onStage(RunStage.GENERATING_ANSWER);
            return new SettlementAgent.AgentResult("안전한 정산 답변", List.of("안전한 ", "정산 답변"), 1);
        };
        when(repository.updateStage(eq(run.runId()), any(), eq(NOW))).thenReturn(true);
        when(repository.complete(eq(actorId), eq(run.runId()), any(), eq("안전한 정산 답변"), eq(NOW)))
                .thenReturn(true);
        doThrow(new AiException(AiErrorCode.AI_STATE_UNAVAILABLE))
                .when(publisher).done(run.runId(), "안전한 정산 답변", NOW);
        SettlementRunExecutor executor = new SettlementRunExecutor(
                repository,
                agent,
                publisher,
                CLOCK,
                new SimpleMeterRegistry()
        );

        executor.execute(run, List.of());

        InOrder order = inOrder(repository, publisher);
        order.verify(repository).updateStage(run.runId(), RunStage.ANALYZING, NOW);
        order.verify(publisher).progress(run.runId(), RunStage.ANALYZING, NOW);
        order.verify(repository).updateStage(run.runId(), RunStage.FETCHING_DATA, NOW);
        order.verify(publisher).progress(run.runId(), RunStage.FETCHING_DATA, NOW);
        order.verify(repository).updateStage(run.runId(), RunStage.GENERATING_ANSWER, NOW);
        order.verify(publisher).progress(run.runId(), RunStage.GENERATING_ANSWER, NOW);
        order.verify(repository).complete(eq(actorId), eq(run.runId()), any(), eq("안전한 정산 답변"), eq(NOW));
        order.verify(publisher).delta(run.runId(), 1L, "안전한 ", NOW);
        order.verify(publisher).delta(run.runId(), 2L, "정산 답변", NOW);
        order.verify(publisher).done(run.runId(), "안전한 정산 답변", NOW);
        verify(repository, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void completionFencingFailurePublishesNoAnswer() {
        UUID actorId = UUID.randomUUID();
        AgentRun run = AgentRun.start(UUID.randomUUID(), actorId, "정산 요약", NOW, Duration.ofSeconds(90));
        SettlementChatStateRepository repository = mock(SettlementChatStateRepository.class);
        SettlementRunEventPublisher publisher = mock(SettlementRunEventPublisher.class);
        SettlementAgent agent = request -> new SettlementAgent.AgentResult("정산 답변", List.of("정산 답변"), 0);
        when(repository.updateStage(run.runId(), RunStage.ANALYZING, NOW)).thenReturn(true);
        when(repository.complete(eq(actorId), eq(run.runId()), any(), eq("정산 답변"), eq(NOW)))
                .thenReturn(false);
        SettlementRunExecutor executor = new SettlementRunExecutor(
                repository,
                agent,
                publisher,
                CLOCK,
                new SimpleMeterRegistry()
        );

        executor.execute(run, List.of());

        verify(publisher, never()).delta(any(), any(Long.class), any(), any());
        verify(publisher, never()).done(any(), any(), any());
        verify(repository, never()).fail(any(), any(), any(), any(), any());
    }

    private static SettlementChatApplicationService service(
            SettlementChatStateRepository repository,
            SettlementRunExecutor runExecutor,
            SettlementRunEventPublisher publisher,
            SettlementRunConcurrencyLimiter concurrencyLimiter,
            SettlementRunTaskRegistry taskRegistry,
            Executor executor,
            boolean enabled
    ) {
        return new SettlementChatApplicationService(
                repository,
                runExecutor,
                publisher,
                concurrencyLimiter,
                taskRegistry,
                executor,
                properties(enabled),
                CLOCK,
                new SimpleMeterRegistry()
        );
    }

    private static AiSettlementProperties properties(boolean enabled) {
        return properties(enabled, 4);
    }

    private static AiSettlementProperties properties(boolean enabled, int maxConcurrentRuns) {
        return new AiSettlementProperties(
                "gpt-5.6-luna",
                "low",
                2_000,
                8_000,
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                new AiSettlementProperties.Execution(maxConcurrentRuns),
                new AiSettlementProperties.Conversation(Duration.ofHours(24), 20),
                new AiSettlementProperties.Sse(Duration.ofSeconds(15)),
                new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(enabled)),
                "internal-token"
        );
    }
}
