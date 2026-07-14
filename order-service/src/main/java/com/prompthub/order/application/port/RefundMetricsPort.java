package com.prompthub.order.application.port;

import java.time.Duration;

public interface RefundMetricsPort {

    void recordRequested();

    void recordCompleted();

    void recordFailed(boolean retryable);

    void recordUnknown();

    void recordGrpcResult(String status);

    void recordManualReview();

    void recordReconciliationDelay(Duration delay);
}
