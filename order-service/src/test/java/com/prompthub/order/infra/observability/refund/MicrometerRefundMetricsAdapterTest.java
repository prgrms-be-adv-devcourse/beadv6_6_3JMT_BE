package com.prompthub.order.infra.observability.refund;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerRefundMetricsAdapterTest {

    @Test
    void recordsRefundLifecycleAndReconciliationMetricsWithoutIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerRefundMetricsAdapter metrics = new MicrometerRefundMetricsAdapter(registry);

        metrics.recordRequested();
        metrics.recordCompleted();
        metrics.recordFailed(true);
        metrics.recordUnknown();
        metrics.recordGrpcResult("PROCESSING");
        metrics.recordManualReview();
        metrics.recordReconciliationDelay(Duration.ofSeconds(3));

        assertThat(registry.get("order.refund.requested").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.refund.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.refund.failed").tag("retryable", "true").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.refund.unknown").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.refund.grpc.result").tag("status", "PROCESSING").counter().count())
            .isEqualTo(1);
        assertThat(registry.get("order.refund.manual.review").counter().count()).isEqualTo(1);
        assertThat(registry.get("order.refund.reconciliation.delay").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(3);
    }
}
