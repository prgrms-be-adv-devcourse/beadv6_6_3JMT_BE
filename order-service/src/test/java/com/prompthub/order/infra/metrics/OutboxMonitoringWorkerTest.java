package com.prompthub.order.infra.metrics;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxMonitoringWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneOffset.UTC);
    private static final OutboxMonitorProperties PROPERTIES = new OutboxMonitorProperties(
        true, 60_000L, 1L, 300_000L
    );
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private OutboxMetrics metrics;

    @Test
    void isEnabledOnlyWhenOutboxMonitoringIsEnabled() {
        ConditionalOnProperty condition = OutboxMonitoringWorker.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.prefix()).isEqualTo("prompthub.outbox-monitor");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    void logsFailedBacklogBreachAndRecoveryOnlyOnStateTransitions(CapturedOutput output) {
        OutboxMonitoringWorker worker = worker();
        given(repository.countByStatus(OutboxEventStatus.FAILED)).willReturn(2L, 2L, 0L);
        given(repository.findOldestUnpublishedOccurredAt()).willReturn(Optional.empty());

        worker.monitor();
        worker.monitor();
        worker.monitor();

        assertTransitionLog(output, "outbox_failed", "failed_count", "2", "1");
        assertThat(output.getOut()).containsOnlyOnce("alert=outbox_failed state=breached")
            .containsOnlyOnce("alert=outbox_failed state=recovered");
        then(metrics).should(times(2)).updateBacklog(2L, java.time.Duration.ZERO);
        then(metrics).should().updateBacklog(0L, java.time.Duration.ZERO);
        assertNoIdentifiers(output);
    }

    @Test
    void logsOldestUnpublishedBreachAndRecoveryOnlyOnStateTransitions(CapturedOutput output) {
        OutboxMonitoringWorker worker = worker();
        LocalDateTime sixMinutesAgo = LocalDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone()).minusMinutes(6);
        given(repository.countByStatus(OutboxEventStatus.FAILED)).willReturn(0L);
        given(repository.findOldestUnpublishedOccurredAt()).willReturn(
            Optional.of(sixMinutesAgo), Optional.of(sixMinutesAgo), Optional.empty()
        );

        worker.monitor();
        worker.monitor();
        worker.monitor();

        assertTransitionLog(output, "outbox_oldest_unpublished", "age_seconds", "360", "300");
        assertThat(output.getOut()).containsOnlyOnce("alert=outbox_oldest_unpublished state=breached")
            .containsOnlyOnce("alert=outbox_oldest_unpublished state=recovered");
        assertNoIdentifiers(output);
    }

    @Test
    void isolatesRepositoryAndMetricFailures() {
        OutboxMonitoringWorker worker = worker();
        given(repository.countByStatus(OutboxEventStatus.FAILED))
            .willThrow(new IllegalStateException("database unavailable"))
            .willReturn(0L);
        given(repository.findOldestUnpublishedOccurredAt()).willReturn(Optional.empty());

        assertThatCode(worker::monitor).doesNotThrowAnyException();

        then(repository).should().findOldestUnpublishedOccurredAt();

        doThrow(new IllegalStateException("metrics unavailable"))
            .when(metrics).updateBacklog(0L, java.time.Duration.ZERO);

        assertThatCode(worker::monitor).doesNotThrowAnyException();
    }

    private OutboxMonitoringWorker worker() {
        return new OutboxMonitoringWorker(repository, metrics, PROPERTIES, CLOCK);
    }

    private void assertTransitionLog(
        CapturedOutput output,
        String alert,
        String metric,
        String value,
        String threshold
    ) {
        assertThat(output.getOut()).contains(
            "alert=" + alert + " state=breached metric=" + metric + " value=" + value + " threshold=" + threshold,
            "alert=" + alert + " state=recovered metric=" + metric + " value=0 threshold=" + threshold
        );
    }

    private void assertNoIdentifiers(CapturedOutput output) {
        assertThat(output.getOut()).doesNotContain("eventId=", "aggregateId=", "requestedBy=");
        assertThat(UUID_PATTERN.matcher(output.getOut()).find()).isFalse();
    }
}
