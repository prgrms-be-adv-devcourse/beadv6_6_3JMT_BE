package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "prompthub.outbox-monitor",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class OutboxMonitoringWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMetrics outboxMetrics;
    private final OutboxMonitorProperties properties;
    private final Clock clock;

    private boolean failedThresholdBreached;
    private boolean oldestThresholdBreached;

    public OutboxMonitoringWorker(
        OutboxEventRepository outboxEventRepository,
        OutboxMetrics outboxMetrics,
        OutboxMonitorProperties properties,
        Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxMetrics = outboxMetrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${prompthub.outbox-monitor.fixed-delay-ms:60000}")
    public synchronized void monitor() {
        Long failedCount = readFailedCount();
        Duration oldestUnpublishedAge = readOldestUnpublishedAge();

        if (failedCount != null && oldestUnpublishedAge != null) {
            updateBacklogQuietly(failedCount, oldestUnpublishedAge);
        }
        if (failedCount != null) {
            monitorFailedThreshold(failedCount);
        }
        if (oldestUnpublishedAge != null) {
            monitorOldestThreshold(oldestUnpublishedAge);
        }
    }

    private Long readFailedCount() {
        try {
            return Math.max(0L, outboxEventRepository.countByStatus(OutboxEventStatus.FAILED));
        } catch (RuntimeException exception) {
            log.warn("Outbox failed-event backlog measurement failed.");
            return null;
        }
    }

    private Duration readOldestUnpublishedAge() {
        try {
            Optional<LocalDateTime> oldestOccurredAt = outboxEventRepository.findOldestUnpublishedOccurredAt();
            if (oldestOccurredAt.isEmpty()) {
                return Duration.ZERO;
            }
            Duration age = Duration.between(oldestOccurredAt.get(), LocalDateTime.now(clock));
            return age.isNegative() ? Duration.ZERO : age;
        } catch (RuntimeException exception) {
            log.warn("Outbox oldest-unpublished backlog measurement failed.");
            return null;
        }
    }

    private void updateBacklogQuietly(long failedCount, Duration oldestUnpublishedAge) {
        try {
            outboxMetrics.updateBacklog(failedCount, oldestUnpublishedAge);
        } catch (RuntimeException exception) {
            log.warn("Outbox backlog metric recording failed.");
        }
    }

    private void monitorFailedThreshold(long failedCount) {
        boolean breached = failedCount >= properties.failedCountThreshold();
        if (breached != failedThresholdBreached) {
            logThresholdTransition(
                "outbox_failed", breached, "failed_count", failedCount, properties.failedCountThreshold()
            );
            failedThresholdBreached = breached;
        }
    }

    private void monitorOldestThreshold(Duration oldestUnpublishedAge) {
        long oldestAgeSeconds = Math.max(0L, oldestUnpublishedAge.toSeconds());
        long thresholdSeconds = Duration.ofMillis(properties.oldestUnpublishedThresholdMs()).toSeconds();
        boolean breached = oldestUnpublishedAge.compareTo(
            Duration.ofMillis(properties.oldestUnpublishedThresholdMs())
        ) >= 0;
        if (breached != oldestThresholdBreached) {
            logThresholdTransition(
                "outbox_oldest_unpublished", breached, "age_seconds", oldestAgeSeconds, thresholdSeconds
            );
            oldestThresholdBreached = breached;
        }
    }

    private void logThresholdTransition(
        String alert,
        boolean breached,
        String metric,
        long value,
        long threshold
    ) {
        String state = breached ? "breached" : "recovered";
        if (breached) {
            log.warn(
                "Outbox threshold transition. alert={} state={} metric={} value={} threshold={}",
                alert, state, metric, value, threshold
            );
        } else {
            log.info(
                "Outbox threshold transition. alert={} state={} metric={} value={} threshold={}",
                alert, state, metric, value, threshold
            );
        }
    }
}
