package com.prompthub.ai.settlement.presentation.sse;

import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.domain.AgentRun;
import com.prompthub.ai.settlement.domain.RunStage;
import com.prompthub.ai.settlement.domain.RunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SseEmitterRegistry {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<EmitterSession>> sessionsByRun =
            new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();

    public SseEmitterRegistry() {
        this(Metrics.globalRegistry);
    }

    @Autowired
    public SseEmitterRegistry(MeterRegistry meterRegistry) {
        meterRegistry.gauge("ai.sse.connections", connectionCount);
    }

    public void register(UUID runId, SseEmitter emitter, boolean acceptDeltas) {
        EmitterSession session = new EmitterSession(
                UUID.randomUUID(), emitter, acceptDeltas, new AtomicBoolean());
        sessionsByRun.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(session);
        connectionCount.incrementAndGet();
        emitter.onCompletion(() -> remove(runId, session));
        emitter.onTimeout(() -> remove(runId, session));
        emitter.onError(ignored -> remove(runId, session));
    }

    public void sendSnapshot(UUID runId, SseEmitter emitter, AgentRun run) {
        if (!runId.equals(run.runId()) || run.status().isTerminal()) {
            throw new IllegalArgumentException("RUNNING run snapshot만 전송할 수 있습니다.");
        }
        EmitterSession session = findSession(runId, emitter);
        if (session == null) {
            return;
        }
        send(runId, session, SseEmitter.event()
                .name("snapshot")
                .data(new SnapshotPayload(
                        run.runId(),
                        run.status(),
                        run.stage(),
                        run.startedAt(),
                        run.deadlineAt()
                )));
    }

    public void sendTerminal(UUID runId, SseEmitter emitter, AgentRun run) {
        if (!runId.equals(run.runId()) || !run.status().isTerminal()) {
            throw new IllegalArgumentException("terminal run만 전송할 수 있습니다.");
        }
        RunEvent event = terminalEvent(run);
        EmitterSession registered = findSession(runId, emitter);
        if (registered == null) {
            try {
                emitter.send(toSseEvent(event));
            } catch (IOException | IllegalStateException ignored) {
                // 연결 생성 중 끊어진 client는 run 상태에 영향을 주지 않는다.
            } finally {
                emitter.complete();
            }
            return;
        }
        send(runId, registered, toSseEvent(event));
        registered.emitter().complete();
        remove(runId, registered);
    }

    public void dispatch(RunEvent event) {
        CopyOnWriteArrayList<EmitterSession> sessions = sessionsByRun.get(event.runId());
        if (sessions == null) {
            return;
        }

        for (EmitterSession session : sessions) {
            if (event.type() == RunEvent.RunEventType.DELTA && !session.acceptDeltas()) {
                continue;
            }
            send(event.runId(), session, toSseEvent(event));
        }

        if (event.terminal()) {
            for (EmitterSession session : sessions) {
                session.emitter().complete();
                remove(event.runId(), session);
            }
        }
    }

    public void heartbeat() {
        sessionsByRun.forEach((runId, sessions) -> {
            for (EmitterSession session : sessions) {
                send(runId, session, SseEmitter.event().comment("heartbeat"));
            }
        });
    }

    public int connectionCount(UUID runId) {
        CopyOnWriteArrayList<EmitterSession> sessions = sessionsByRun.get(runId);
        return sessions == null ? 0 : sessions.size();
    }

    private EmitterSession findSession(UUID runId, SseEmitter emitter) {
        CopyOnWriteArrayList<EmitterSession> sessions = sessionsByRun.get(runId);
        if (sessions == null) {
            return null;
        }
        return sessions.stream()
                .filter(session -> session.emitter() == emitter)
                .findFirst()
                .orElse(null);
    }

    private void send(UUID runId, EmitterSession session, SseEmitter.SseEventBuilder event) {
        try {
            session.emitter().send(event);
        } catch (IOException | IllegalStateException exception) {
            remove(runId, session);
        }
    }

    private void remove(UUID runId, EmitterSession session) {
        if (!session.removed().compareAndSet(false, true)) {
            return;
        }
        sessionsByRun.computeIfPresent(runId, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
        connectionCount.decrementAndGet();
    }

    private static RunEvent terminalEvent(AgentRun run) {
        return switch (run.status()) {
            case COMPLETED -> RunEvent.done(run.runId(), run.answer(), run.completedAt());
            case FAILED -> RunEvent.failed(
                    run.runId(), run.errorCode(), run.errorMessage(), run.failedAt());
            case CANCELLED -> RunEvent.cancelled(run.runId(), run.cancelledAt());
            case RUNNING -> throw new IllegalArgumentException("RUNNING run은 terminal event가 아닙니다.");
        };
    }

    private static SseEmitter.SseEventBuilder toSseEvent(RunEvent event) {
        Object data = switch (event.type()) {
            case PROGRESS -> new ProgressPayload(event.runId(), event.stage(), event.occurredAt());
            case DELTA -> new DeltaPayload(event.runId(), event.sequence(), event.text());
            case DONE -> new DonePayload(event.runId(), event.text(), event.occurredAt());
            case FAILED -> new FailedPayload(event.runId(), event.code(), event.text(), event.occurredAt());
            case CANCELLED -> new CancelledPayload(event.runId(), event.occurredAt());
        };
        return SseEmitter.event().name(event.type().eventName()).data(data);
    }

    private record EmitterSession(
            UUID sessionId,
            SseEmitter emitter,
            boolean acceptDeltas,
            AtomicBoolean removed
    ) {
    }

    private record SnapshotPayload(
            UUID runId,
            RunStatus status,
            RunStage stage,
            Instant startedAt,
            Instant deadlineAt
    ) {
    }

    private record ProgressPayload(UUID runId, RunStage stage, Instant occurredAt) {
    }

    private record DeltaPayload(UUID runId, long sequence, String text) {
    }

    private record DonePayload(UUID runId, String answer, Instant completedAt) {
    }

    private record FailedPayload(UUID runId, String code, String message, Instant failedAt) {
    }

    private record CancelledPayload(UUID runId, Instant cancelledAt) {
    }
}
