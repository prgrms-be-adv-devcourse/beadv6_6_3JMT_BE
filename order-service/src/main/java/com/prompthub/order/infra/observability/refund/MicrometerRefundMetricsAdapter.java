package com.prompthub.order.infra.observability.refund;

import com.prompthub.order.application.port.RefundMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerRefundMetricsAdapter implements RefundMetricsPort {

    private static final String PREFIX = "order.refund";

    private final MeterRegistry meterRegistry;
    private final Counter requested;
    private final Counter completed;
    private final Counter unknown;
    private final Counter manualReview;
    private final Timer reconciliationDelay;

    public MicrometerRefundMetricsAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.requested = meterRegistry.counter(PREFIX + ".requested");
        this.completed = meterRegistry.counter(PREFIX + ".completed");
        this.unknown = meterRegistry.counter(PREFIX + ".unknown");
        this.manualReview = meterRegistry.counter(PREFIX + ".manual.review");
        this.reconciliationDelay = Timer.builder(PREFIX + ".reconciliation.delay")
            .description("Delay between the scheduled and claimed refund reconciliation time")
            .register(meterRegistry);
    }

    @Override
    public void recordRequested() {
        requested.increment();
    }

    @Override
    public void recordCompleted() {
        completed.increment();
    }

    @Override
    public void recordFailed(boolean retryable) {
        meterRegistry.counter(PREFIX + ".failed", "retryable", Boolean.toString(retryable)).increment();
    }

    @Override
    public void recordUnknown() {
        unknown.increment();
    }

    @Override
    public void recordGrpcResult(String status) {
        meterRegistry.counter(PREFIX + ".grpc.result", "status", status).increment();
    }

    @Override
    public void recordManualReview() {
        manualReview.increment();
    }

    @Override
    public void recordReconciliationDelay(Duration delay) {
        reconciliationDelay.record(delay.isNegative() ? Duration.ZERO : delay);
    }
}
