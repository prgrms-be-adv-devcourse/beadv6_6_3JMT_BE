package com.prompthub.order.infra.messaging.kafka.producer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.PublishOutcome;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RetryOutcome;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T01:02:03Z"), ZoneOffset.UTC);
	private static final LocalDateTime ATTEMPTED_AT = LocalDateTime.of(2026, 8, 5, 1, 2, 3);
	private static final String TOPIC = "order-events";
	private static final String OWNER = "relay-a";

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private KafkaTemplate<String, String> kafkaTemplate;

	@Mock
	private OutboxMetrics outboxMetrics;

	private ListAppender<ILoggingEvent> logAppender;

	@AfterEach
	void tearDown() {
		if (logAppender != null) {
			Logger logger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
			logger.detachAppender(logAppender);
			logAppender.stop();
		}
	}

	@Test
	@DisplayName("claim한 이벤트를 aggregateId key로 발행하고 같은 claim owner로 완료한다")
	void publishPendingEvents_marksPublishedWithClaimOwner() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any())).willReturn(true);

		relay.publishPendingEvents();

		then(kafkaTemplate).should().send(TOPIC, event.getAggregateId().toString(), event.getPayload());
		then(outboxEventRepository).should().markPublished(eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT));
		then(outboxMetrics).should().recordPublish(PublishOutcome.SUCCESS);
	}

	@Test
	@DisplayName("Kafka 완료 시각으로 publish audit을 기록한다")
	void publishPendingEvents_recordsSuccessAtKafkaCompletionTime() {
		MutableClock clock = new MutableClock(ATTEMPTED_AT.toInstant(ZoneOffset.UTC));
		LocalDateTime completedAt = ATTEMPTED_AT.plusSeconds(7);
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(clock, () -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(completesAt(clock, completedAt));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any())).willReturn(true);

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(event.getEventId()), eq(OWNER), eq(completedAt));
	}

	@Test
	@DisplayName("Kafka 발행 timeout은 owner-checked 재시도를 기록하고 다음 claim을 중단한다")
	void publishPendingEvents_recordsTimeoutAndStopsEarly() {
		OutboxEvent timedOutEvent = pendingEvent("ORDER_PAID");
		OutboxEvent nextEvent = pendingEvent("ORDER_REFUND");
		OutboxRelay relay = relay(properties(1L), () -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(timedOutEvent), Optional.of(nextEvent));
		given(kafkaTemplate.send(TOPIC, timedOutEvent.getAggregateId().toString(), timedOutEvent.getPayload()))
			.willReturn(new CompletableFuture<>());

		relay.publishPendingEvents();

		ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
		then(outboxEventRepository).should().recordPublishFailure(
			eq(timedOutEvent.getEventId()), eq(OWNER), eq(ATTEMPTED_AT), errorCaptor.capture(), any()
		);
		assertThat(errorCaptor.getValue()).startsWith(TimeoutException.class.getSimpleName());
		then(outboxEventRepository).should(times(1))
			.claimNextPublishable(any(), eq(OWNER), any());
		then(kafkaTemplate).should(never()).send(TOPIC, nextEvent.getAggregateId().toString(), nextEvent.getPayload());
	}

	@Test
	@DisplayName("Kafka timeout 시각으로 retry backoff 기준을 기록한다")
	void publishPendingEvents_recordsFailureAtKafkaFailureTime() {
		MutableClock clock = new MutableClock(ATTEMPTED_AT.toInstant(ZoneOffset.UTC));
		LocalDateTime timedOutAt = ATTEMPTED_AT.plusSeconds(7);
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(clock, () -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any())).willReturn(Optional.of(event));
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(timesOutAt(clock, timedOutAt));

		relay.publishPendingEvents();

		then(outboxEventRepository).should().recordPublishFailure(
			eq(event.getEventId()), eq(OWNER), eq(timedOutAt), any(), any()
		);
	}

	@Test
	@DisplayName("Kafka execution failure은 owner-checked 재시도를 기록한다")
	void publishPendingEvents_recordsExecutionFailure() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable\nsecret=ignored")));

		relay.publishPendingEvents();

		ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
		then(outboxEventRepository).should().recordPublishFailure(
			eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT), errorCaptor.capture(), any()
		);
		assertThat(errorCaptor.getValue()).isEqualTo("IllegalStateException: broker unavailable secret=[REDACTED]");
		then(outboxMetrics).should().recordPublish(PublishOutcome.FAILURE);
	}

	@Test
	@DisplayName("Kafka 대기 중 interrupt되면 실패를 기록하고 interrupt 상태를 복원한 뒤 중단한다")
	void publishPendingEvents_restoresInterruptAndStops() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(new CompletableFuture<>());

		Thread.currentThread().interrupt();
		try {
			relay.publishPendingEvents();

			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			then(outboxEventRepository).should().recordPublishFailure(
				eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT), any(), any()
			);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("interrupt 실패 저장이 예외여도 interrupt 상태를 복원하고 다음 claim을 중단한다")
	void publishPendingEvents_restoresInterruptWhenFailurePersistenceThrows() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(new CompletableFuture<>());
		given(outboxEventRepository.recordPublishFailure(any(), any(), any(), any(), any()))
			.willThrow(new IllegalStateException("persistence unavailable"));

		Thread.currentThread().interrupt();
		try {
			relay.publishPendingEvents();

			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			then(outboxEventRepository).should(times(1)).claimNextPublishable(any(), eq(OWNER), any());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("만료되어 다른 owner가 재claim한 이벤트는 이전 owner가 완료 처리하지 못하면 중단한다")
	void publishPendingEvents_stopsWhenCompletionOwnerIsStale() {
		OutboxEvent staleEvent = pendingEvent("ORDER_PAID");
		OutboxEvent nextEvent = pendingEvent("ORDER_REFUND");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(staleEvent), Optional.of(nextEvent));
		given(kafkaTemplate.send(TOPIC, staleEvent.getAggregateId().toString(), staleEvent.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(staleEvent.getEventId()), eq(OWNER), any())).willReturn(false);

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(staleEvent.getEventId()), eq(OWNER), eq(ATTEMPTED_AT));
		then(kafkaTemplate).should(never()).send(TOPIC, nextEvent.getAggregateId().toString(), nextEvent.getPayload());
	}

	@Test
	@DisplayName("claim 저장 예외는 스케줄러 밖으로 전파하지 않고 정제된 경고만 남긴다")
	void publishPendingEvents_doesNotPropagateClaimPersistenceFailure() {
		OutboxRelay relay = relay(() -> OWNER);
		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willThrow(new IllegalStateException("claim unavailable secret=relay-credential"));
		logAppender = startLogCapture();

		assertThatCode(relay::publishPendingEvents).doesNotThrowAnyException();

		then(kafkaTemplate).shouldHaveNoInteractions();
		assertSanitizedRelayWarning("아웃박스 이벤트 claim 처리 중 예외가 발생했습니다.");
	}

	@Test
	@DisplayName("Kafka 성공 뒤 완료 저장 예외는 실패 재시도나 backoff로 전환하지 않는다")
	void publishPendingEvents_doesNotRecordFailureWhenCompletionPersistenceFails() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event));
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any()))
			.willThrow(new IllegalStateException("completion unavailable secret=relay-credential"));
		logAppender = startLogCapture();

		assertThatCode(relay::publishPendingEvents).doesNotThrowAnyException();

		then(outboxEventRepository).should(never()).recordPublishFailure(any(), any(), any(), any(), any());
		then(outboxMetrics).should().recordPublish(PublishOutcome.SUCCESS);
		assertSanitizedRelayWarning("아웃박스 이벤트 완료 처리 중 예외가 발생했습니다.");
	}

	@Test
	@DisplayName("Kafka 실패 뒤 실패 저장 예외는 스케줄러 밖으로 전파하지 않고 정제된 경고만 남긴다")
	void publishPendingEvents_doesNotPropagateFailurePersistenceFailure() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event));
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
		given(outboxEventRepository.recordPublishFailure(any(), any(), any(), any(), any()))
			.willThrow(new IllegalStateException("failure persistence unavailable secret=relay-credential"));
		logAppender = startLogCapture();

		assertThatCode(relay::publishPendingEvents).doesNotThrowAnyException();

		then(outboxMetrics).should().recordPublish(PublishOutcome.FAILURE);
		assertSanitizedRelayWarning("아웃박스 이벤트 실패 처리 중 예외가 발생했습니다.");
	}

	@Test
	@DisplayName("재시도 이벤트가 성공하면 claim owner로 완료 처리한다")
	void publishPendingEvents_marksRetriedEventPublished() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		event.recordPublishFailure(ATTEMPTED_AT.minusSeconds(5), "previous failure", retryProperties().toPolicy());
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any())).willReturn(true);

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT));
		then(outboxMetrics).should().recordPublish(PublishOutcome.SUCCESS);
		then(outboxMetrics).should().recordRetry(RetryOutcome.SUCCESS);
	}

	@Test
	@DisplayName("발행 지표 기록 실패는 성공한 발행 처리를 변경하지 않는다")
	void publishPendingEvents_ignoresPublishMetricFailure() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any())).willReturn(true);
		willThrow(new IllegalStateException("metrics unavailable\\nsecret=ignored"))
			.given(outboxMetrics).recordPublish(PublishOutcome.SUCCESS);
		logAppender = startLogCapture();

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT));
		assertMetricFallbackLog("Outbox publish metric recording failed.", "outcome=SUCCESS");
	}

	@Test
	@DisplayName("재시도 지표 기록 실패는 발행 처리를 변경하지 않고 stack trace를 남기지 않는다")
	void publishPendingEvents_ignoresRetryMetricFailureWithoutThrowableLog() {
		OutboxEvent event = pendingEvent("ORDER_PAID");
		event.recordPublishFailure(ATTEMPTED_AT.minusSeconds(5), "previous failure", retryProperties().toPolicy());
		OutboxRelay relay = relay(() -> OWNER);

		given(outboxEventRepository.claimNextPublishable(any(), eq(OWNER), any()))
			.willReturn(Optional.of(event), Optional.empty());
		given(kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(eq(event.getEventId()), eq(OWNER), any())).willReturn(true);
		willThrow(new IllegalStateException("metrics unavailable\\nsecret=ignored"))
			.given(outboxMetrics).recordRetry(RetryOutcome.SUCCESS);
		logAppender = startLogCapture();

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(event.getEventId()), eq(OWNER), eq(ATTEMPTED_AT));
		assertMetricFallbackLog("Outbox retry metric recording failed.", "outcome=SUCCESS");
	}

	@Test
	@DisplayName("각 claim 시도는 relay identity와 구분되는 새 owner token을 사용한다")
	void publishPendingEvents_usesNewOwnerTokenForEachClaim() {
		OutboxEvent first = pendingEvent("ORDER_PAID");
		OutboxEvent second = pendingEvent("ORDER_REFUND");
		AtomicInteger tokenNumber = new AtomicInteger();
		Supplier<String> owners = () -> "relay-a-" + tokenNumber.incrementAndGet();
		OutboxRelay relay = relay(owners);

		given(outboxEventRepository.claimNextPublishable(any(), any(), any()))
			.willReturn(Optional.of(first), Optional.of(second), Optional.empty());
		given(kafkaTemplate.send(TOPIC, first.getAggregateId().toString(), first.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(kafkaTemplate.send(TOPIC, second.getAggregateId().toString(), second.getPayload()))
			.willReturn(CompletableFuture.completedFuture(null));
		given(outboxEventRepository.markPublished(any(), any(), any())).willReturn(true);

		relay.publishPendingEvents();

		then(outboxEventRepository).should().markPublished(eq(first.getEventId()), eq("relay-a-1"), eq(ATTEMPTED_AT));
		then(outboxEventRepository).should().markPublished(eq(second.getEventId()), eq("relay-a-2"), eq(ATTEMPTED_AT));
	}

	private OutboxRelay relay(Supplier<String> owners) {
		return relay(properties(100L), owners);
	}

	private OutboxRelay relay(OutboxRelayProperties properties, Supplier<String> owners) {
		return relay(CLOCK, properties, owners);
	}

	private OutboxRelay relay(Clock clock, Supplier<String> owners) {
		return relay(clock, properties(100L), owners);
	}

	private OutboxRelay relay(Clock clock, OutboxRelayProperties properties, Supplier<String> owners) {
		return new OutboxRelay(
			outboxEventRepository,
			kafkaTemplate,
			properties,
			retryProperties(),
			clock,
			outboxMetrics,
			owners
		);
	}

	private OutboxRelayProperties properties(long publishTimeoutMs) {
		return new OutboxRelayProperties(true, 5_000L, 100, publishTimeoutMs, 30_000L, TOPIC);
	}

	private OutboxRetryProperties retryProperties() {
		return new OutboxRetryProperties(5_000L, 300_000L, 10);
	}

	private OutboxEvent pendingEvent(String eventType) {
		return OutboxEvent.create(
			UUID.randomUUID(), UUID.randomUUID(), eventType, "{\"eventType\":\"" + eventType + "\"}", ATTEMPTED_AT
		);
	}

	private ListAppender<ILoggingEvent> startLogCapture() {
		Logger logger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void assertMetricFallbackLog(String message, String outcome) {
		assertThat(logAppender.list)
			.filteredOn(event -> event.getFormattedMessage().contains(message))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getFormattedMessage()).contains(outcome).doesNotContain("secret=ignored");
				assertThat(event.getThrowableProxy()).isNull();
			});
	}

	private void assertSanitizedRelayWarning(String message) {
		assertThat(logAppender.list)
			.filteredOn(event -> event.getFormattedMessage().contains(message))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getFormattedMessage()).doesNotContain("secret=relay-credential");
				assertThat(event.getThrowableProxy()).isNull();
			});
	}

	private CompletableFuture<SendResult<String, String>> completesAt(MutableClock clock, LocalDateTime completedAt) {
		return new CompletableFuture<>() {
			@Override
			public SendResult<String, String> get(long timeout, java.util.concurrent.TimeUnit unit) {
				clock.set(completedAt.toInstant(ZoneOffset.UTC));
				return null;
			}
		};
	}

	private CompletableFuture<SendResult<String, String>> timesOutAt(MutableClock clock, LocalDateTime timedOutAt) {
		return new CompletableFuture<>() {
			@Override
			public SendResult<String, String> get(long timeout, java.util.concurrent.TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
				clock.set(timedOutAt.toInstant(ZoneOffset.UTC));
				throw new TimeoutException("publish timed out");
			}
		};
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void set(Instant instant) {
			this.instant = instant;
		}
	}
}
