package com.prompthub.ai.settlement.domain;

import com.prompthub.ai.settlement.domain.exception.InvalidRunStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentRun(
        UUID runId,
        UUID conversationId,
        UUID actorId,
        String question,
        RunStatus status,
        RunStage stage,
        Instant startedAt,
        Instant deadlineAt,
        Instant completedAt,
        Instant failedAt,
        Instant cancelledAt,
        String answer,
        String errorCode,
        String errorMessage
) {

    public AgentRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (runId.version() != 4) {
            throw new IllegalArgumentException("runId는 UUID v4여야 합니다.");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question은 비어 있을 수 없습니다.");
        }
        question = question.strip();
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt은 startedAt보다 늦어야 합니다.");
        }
        validateTerminalTime(startedAt, deadlineAt, completedAt, failedAt, cancelledAt);
        validateState(status, stage, completedAt, failedAt, cancelledAt, answer, errorCode, errorMessage);
    }

    public static AgentRun start(
            UUID conversationId,
            UUID actorId,
            String question,
            Instant startedAt,
            Duration timeout
    ) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 양수여야 합니다.");
        }
        return new AgentRun(
                UUID.randomUUID(),
                conversationId,
                actorId,
                question,
                RunStatus.RUNNING,
                RunStage.ANALYZING,
                startedAt,
                startedAt.plus(timeout),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Redis run hash를 신뢰된 도메인 상태로 재구성한다.
     * 임의 상태 전이용으로 사용하지 않고 persistence adapter에서만 호출한다.
     */
    public static AgentRun restore(
            UUID runId,
            UUID conversationId,
            UUID actorId,
            String question,
            RunStatus status,
            RunStage stage,
            Instant startedAt,
            Instant deadlineAt,
            Instant completedAt,
            Instant failedAt,
            Instant cancelledAt,
            String answer,
            String errorCode,
            String errorMessage
    ) {
        return new AgentRun(
                runId,
                conversationId,
                actorId,
                question,
                status,
                stage,
                startedAt,
                deadlineAt,
                completedAt,
                failedAt,
                cancelledAt,
                answer,
                errorCode,
                errorMessage
        );
    }

    public AgentRun updateStage(RunStage nextStage) {
        assertRunning();
        Objects.requireNonNull(nextStage, "nextStage");
        if (nextStage == RunStage.DONE || nextStage.ordinal() < stage.ordinal()) {
            throw new IllegalArgumentException("실행 단계를 완료로 건너뛰거나 역행할 수 없습니다.");
        }
        return copy(RunStatus.RUNNING, nextStage, null, null, null, null, null, null);
    }

    public AgentRun complete(String finalAnswer, Instant terminalAt) {
        assertRunning();
        return copy(RunStatus.COMPLETED, RunStage.DONE, terminalAt, null, null, finalAnswer, null, null);
    }

    public AgentRun fail(String code, String message, Instant terminalAt) {
        assertRunning();
        return copy(RunStatus.FAILED, stage, null, terminalAt, null, null, code, message);
    }

    public AgentRun cancel(Instant terminalAt) {
        assertRunning();
        return copy(RunStatus.CANCELLED, stage, null, null, terminalAt, null, null, null);
    }

    private AgentRun copy(
            RunStatus nextStatus,
            RunStage nextStage,
            Instant nextCompletedAt,
            Instant nextFailedAt,
            Instant nextCancelledAt,
            String nextAnswer,
            String nextErrorCode,
            String nextErrorMessage
    ) {
        return restore(
                runId,
                conversationId,
                actorId,
                question,
                nextStatus,
                nextStage,
                startedAt,
                deadlineAt,
                nextCompletedAt,
                nextFailedAt,
                nextCancelledAt,
                nextAnswer,
                nextErrorCode,
                nextErrorMessage
        );
    }

    private void assertRunning() {
        if (status != RunStatus.RUNNING) {
            throw new InvalidRunStateException(status);
        }
    }

    private static void validateState(
            RunStatus status,
            RunStage stage,
            Instant completedAt,
            Instant failedAt,
            Instant cancelledAt,
            String answer,
            String errorCode,
            String errorMessage
    ) {
        switch (status) {
            case RUNNING -> {
                if (stage == RunStage.DONE || completedAt != null || failedAt != null || cancelledAt != null
                        || answer != null || errorCode != null || errorMessage != null) {
                    throw new IllegalArgumentException("RUNNING run에 terminal 값을 저장할 수 없습니다.");
                }
            }
            case COMPLETED -> {
                if (stage != RunStage.DONE || completedAt == null || answer == null || answer.isBlank()
                        || failedAt != null || cancelledAt != null || errorCode != null || errorMessage != null) {
                    throw new IllegalArgumentException("COMPLETED run의 terminal 값이 올바르지 않습니다.");
                }
            }
            case FAILED -> {
                if (stage == RunStage.DONE || failedAt == null || errorCode == null || errorCode.isBlank()
                        || errorMessage == null || errorMessage.isBlank() || completedAt != null
                        || cancelledAt != null || answer != null) {
                    throw new IllegalArgumentException("FAILED run의 terminal 값이 올바르지 않습니다.");
                }
            }
            case CANCELLED -> {
                if (stage == RunStage.DONE || cancelledAt == null || completedAt != null || failedAt != null
                        || answer != null || errorCode != null || errorMessage != null) {
                    throw new IllegalArgumentException("CANCELLED run의 terminal 값이 올바르지 않습니다.");
                }
            }
        }
    }

    private static void validateTerminalTime(
            Instant startedAt,
            Instant deadlineAt,
            Instant completedAt,
            Instant failedAt,
            Instant cancelledAt
    ) {
        if ((completedAt != null && (completedAt.isBefore(startedAt) || completedAt.isAfter(deadlineAt)))
                || (failedAt != null && failedAt.isBefore(startedAt))
                || (cancelledAt != null && cancelledAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("terminal 시각이 run 시간 범위와 일치하지 않습니다.");
        }
    }
}
