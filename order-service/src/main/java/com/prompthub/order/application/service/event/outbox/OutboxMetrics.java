package com.prompthub.order.application.service.event.outbox;

import java.time.Duration;

public interface OutboxMetrics {

    void recordPublish(PublishOutcome outcome);

    void recordRetry(RetryOutcome outcome);

    void recordRedrive(RedriveOutcome outcome);

    void updateBacklog(long failedCount, Duration oldestUnpublishedAge);

    enum PublishOutcome {
        SUCCESS,
        FAILURE
    }

    enum RetryOutcome {
        SUCCESS,
        FAILURE
    }

    enum RedriveOutcome {
        SUCCESS,
        FAILURE
    }
}
