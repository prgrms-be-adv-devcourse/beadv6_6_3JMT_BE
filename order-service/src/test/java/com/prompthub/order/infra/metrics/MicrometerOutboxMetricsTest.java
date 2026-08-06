package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics.PublishOutcome;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RedriveOutcome;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RetryOutcome;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOutboxMetricsTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    @Test
    void recordsAllOutcomesAndBacklogUsingOnlyLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOutboxMetrics metrics = new MicrometerOutboxMetrics(registry);

        metrics.recordPublish(PublishOutcome.SUCCESS);
        metrics.recordPublish(PublishOutcome.FAILURE);
        metrics.recordRetry(RetryOutcome.SUCCESS);
        metrics.recordRetry(RetryOutcome.FAILURE);
        metrics.recordRedrive(RedriveOutcome.SUCCESS);
        metrics.recordRedrive(RedriveOutcome.FAILURE);
        metrics.updateBacklog(2, Duration.ofMinutes(6));

        assertCounter(registry, "order.outbox.publish.attempts", "success");
        assertCounter(registry, "order.outbox.publish.attempts", "failure");
        assertCounter(registry, "order.outbox.retry.attempts", "success");
        assertCounter(registry, "order.outbox.retry.attempts", "failure");
        assertCounter(registry, "order.outbox.redrive.requests", "success");
        assertCounter(registry, "order.outbox.redrive.requests", "failure");
        assertThat(registry.get("order.outbox.events")
            .tag("status", "failed")
            .gauge()
            .value()).isEqualTo(2);
        assertThat(registry.get("order.outbox.oldest.unpublished.age")
            .gauge()
            .value()).isEqualTo(Duration.ofMinutes(6).toSeconds());
        assertMeterTags(registry, "order.outbox.publish.attempts", "outcome", "success");
        assertMeterTags(registry, "order.outbox.publish.attempts", "outcome", "failure");
        assertMeterTags(registry, "order.outbox.retry.attempts", "outcome", "success");
        assertMeterTags(registry, "order.outbox.retry.attempts", "outcome", "failure");
        assertMeterTags(registry, "order.outbox.redrive.requests", "outcome", "success");
        assertMeterTags(registry, "order.outbox.redrive.requests", "outcome", "failure");
        assertMeterTags(registry, "order.outbox.events", "status", "failed");
        assertThat(registry.get("order.outbox.oldest.unpublished.age")
            .gauge()
            .getId()
            .getTags()).isEmpty();
        assertNoIdentifierTags(registry);
    }

    @Test
    void clampsNegativeBacklogValuesToZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOutboxMetrics metrics = new MicrometerOutboxMetrics(registry);

        metrics.updateBacklog(-1, Duration.ofSeconds(-1));

        assertThat(registry.get("order.outbox.events")
            .tag("status", "failed")
            .gauge()
            .value()).isZero();
        assertThat(registry.get("order.outbox.oldest.unpublished.age")
            .gauge()
            .value()).isZero();
    }

    private void assertCounter(SimpleMeterRegistry registry, String name, String outcome) {
        assertThat(registry.get(name).tag("outcome", outcome).counter().count()).isEqualTo(1);
    }

    private void assertMeterTags(SimpleMeterRegistry registry, String name, String tagKey, String tagValue) {
        assertThat(registry.get(name)
            .tag(tagKey, tagValue)
            .meter()
            .getId()
            .getTags()).containsExactly(Tag.of(tagKey, tagValue));
    }

    private void assertNoIdentifierTags(SimpleMeterRegistry registry) {
        List<Tag> tags = registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .toList();

        assertThat(tags)
            .extracting(Tag::getKey)
            .doesNotContain("eventId", "aggregateId", "requestedBy");
        assertThat(tags)
            .extracting(Tag::getValue)
            .doesNotContain(UUID.randomUUID().toString())
            .noneMatch(value -> UUID_PATTERN.matcher(value).matches());
    }
}
