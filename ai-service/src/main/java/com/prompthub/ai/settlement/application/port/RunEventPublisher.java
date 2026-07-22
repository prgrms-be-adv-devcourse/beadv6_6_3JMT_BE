package com.prompthub.ai.settlement.application.port;

import com.prompthub.ai.settlement.domain.RunStage;

import java.time.Instant;
import java.util.UUID;

public interface RunEventPublisher {

    void progress(UUID runId, RunStage stage, Instant occurredAt);

    void delta(UUID runId, long sequence, String text, Instant occurredAt);

    /** Terminal state를 Redis run 원장에 먼저 commit한 뒤 호출한다. */
    void done(UUID runId, String answer, Instant completedAt);

    /** Terminal state를 Redis run 원장에 먼저 commit한 뒤 호출한다. */
    void failed(UUID runId, String code, String message, Instant failedAt);

    /** Terminal state를 Redis run 원장에 먼저 commit한 뒤 호출한다. */
    void cancelled(UUID runId, Instant cancelledAt);
}
