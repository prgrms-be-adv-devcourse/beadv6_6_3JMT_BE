package com.prompthub.ai.settlement.application.event;

import com.prompthub.ai.settlement.domain.run.RunStage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RunEvent(
        UUID runId,
        RunEventType type,
        long sequence,
        RunStage stage,
        String text,
        String code,
        Instant occurredAt
) {

    public RunEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        validate(type, sequence, stage, text, code);
    }

    public static RunEvent progress(UUID runId, RunStage stage, Instant occurredAt) {
        return new RunEvent(runId, RunEventType.PROGRESS, 0L, stage, null, null, occurredAt);
    }

    public static RunEvent delta(UUID runId, long sequence, String text, Instant occurredAt) {
        return new RunEvent(runId, RunEventType.DELTA, sequence, null, text, null, occurredAt);
    }

    public static RunEvent done(UUID runId, String answer, Instant completedAt) {
        return new RunEvent(runId, RunEventType.DONE, 0L, RunStage.DONE, answer, null, completedAt);
    }

    public static RunEvent failed(UUID runId, String code, String message, Instant failedAt) {
        return new RunEvent(runId, RunEventType.FAILED, 0L, null, message, code, failedAt);
    }

    public static RunEvent cancelled(UUID runId, Instant cancelledAt) {
        return new RunEvent(runId, RunEventType.CANCELLED, 0L, null, null, null, cancelledAt);
    }

    public boolean terminal() {
        return type.terminal();
    }

    private static void validate(
            RunEventType type,
            long sequence,
            RunStage stage,
            String text,
            String code
    ) {
        switch (type) {
            case PROGRESS -> {
                if (stage == null || stage == RunStage.DONE || sequence != 0L || text != null || code != null) {
                    throw new IllegalArgumentException("progress event가 올바르지 않습니다.");
                }
            }
            case DELTA -> {
                if (sequence < 1L || text == null || text.isEmpty() || stage != null || code != null) {
                    throw new IllegalArgumentException("delta event가 올바르지 않습니다.");
                }
            }
            case DONE -> {
                if (stage != RunStage.DONE || text == null || text.isBlank() || sequence != 0L || code != null) {
                    throw new IllegalArgumentException("done event가 올바르지 않습니다.");
                }
            }
            case FAILED -> {
                if (code == null || code.isBlank() || text == null || text.isBlank()
                        || sequence != 0L || stage != null) {
                    throw new IllegalArgumentException("failed event가 올바르지 않습니다.");
                }
            }
            case CANCELLED -> {
                if (sequence != 0L || stage != null || text != null || code != null) {
                    throw new IllegalArgumentException("cancelled event가 올바르지 않습니다.");
                }
            }
        }
    }

    public enum RunEventType {
        PROGRESS("progress", false),
        DELTA("delta", false),
        DONE("done", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true);

        private final String eventName;
        private final boolean terminal;

        RunEventType(String eventName, boolean terminal) {
            this.eventName = eventName;
            this.terminal = terminal;
        }

        public String eventName() {
            return eventName;
        }

        public boolean terminal() {
            return terminal;
        }
    }
}
