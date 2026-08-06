package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MicrometerOutboxMetrics implements OutboxMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong failedEvents = new AtomicLong();
    private final AtomicLong oldestUnpublishedAgeSeconds = new AtomicLong();

    public MicrometerOutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    @Override
    public void recordPublish(PublishOutcome outcome) {
        increment("order.outbox.publish.attempts", outcome);
    }

    @Override
    public void recordRetry(RetryOutcome outcome) {
        increment("order.outbox.retry.attempts", outcome);
    }

    @Override
    public void recordRedrive(RedriveOutcome outcome) {
        increment("order.outbox.redrive.requests", outcome);
    }

    @Override
    public void updateBacklog(long failedCount, Duration oldestUnpublishedAge) {
        failedEvents.set(Math.max(0L, failedCount));
        oldestUnpublishedAgeSeconds.set(
            oldestUnpublishedAge.isNegative() ? 0L : Math.max(0L, oldestUnpublishedAge.toSeconds())
        );
    }

    void registerGauges() {
        Gauge.builder("order.outbox.events", failedEvents, AtomicLong::get)
            .tag("status", "failed")
            .register(meterRegistry);
        Gauge.builder("order.outbox.oldest.unpublished.age", oldestUnpublishedAgeSeconds, AtomicLong::get)
            .register(meterRegistry);
    }

    private void increment(String name, Enum<?> outcome) {
        meterRegistry.counter(name, "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
    }
}
