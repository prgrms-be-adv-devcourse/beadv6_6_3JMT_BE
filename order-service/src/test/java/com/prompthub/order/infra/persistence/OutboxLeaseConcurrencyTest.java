package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.support.ConcurrentScenarioRunner;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxLeaseConcurrencyTest extends PostgreSqlIntegrationTestSupport {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 30);
	private static final LocalDateTime LEASE_UNTIL = NOW.plusSeconds(30);

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	private ConcurrentScenarioRunner runner;

	@BeforeEach
	void setUp() {
		runner = new ConcurrentScenarioRunner(transactionManager, 10);
	}

	@AfterEach
	void tearDown() {
		runner.close();
	}

	@Test
	void concurrentRelaysClaimOneDueEventOnlyOnce() {
		UUID eventId = UUID.randomUUID();
		outboxEventPersistence.saveAndFlush(OutboxEvent.create(
			eventId,
			UUID.randomUUID(),
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			NOW
		));
		AtomicReference<UUID> first = new AtomicReference<>();
		AtomicReference<UUID> second = new AtomicReference<>();

		ConcurrentScenarioRunner.Results results = runner.run(
			() -> first.set(outboxEventRepository.claimNextPublishable(NOW, "relay-a", LEASE_UNTIL)
				.map(OutboxEvent::getEventId)
				.orElse(null)),
			() -> second.set(outboxEventRepository.claimNextPublishable(NOW, "relay-b", LEASE_UNTIL)
				.map(OutboxEvent::getEventId)
				.orElse(null))
		);

		assertThat(results.firstFailure()).isNull();
		assertThat(results.secondFailure()).isNull();
		assertThat(Stream.of(first.get(), second.get()).filter(Objects::nonNull).toList())
			.containsExactly(eventId);
	}

	@Test
	void claimReleasesItsLockBeforeTheCallerTransactionCompletes() throws Exception {
		UUID eventId = UUID.randomUUID();
		outboxEventPersistence.saveAndFlush(OutboxEvent.create(
			eventId,
			UUID.randomUUID(),
			"ORDER_PAID",
			"{\"eventType\":\"ORDER_PAID\"}",
			NOW
		));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		AtomicReference<Future<Boolean>> completion = new AtomicReference<>();

		try {
			Boolean completedBeforeOuterTransaction = new TransactionTemplate(transactionManager).execute(status -> {
				outboxEventRepository.claimNextPublishable(NOW, "relay-a", LEASE_UNTIL).orElseThrow();
				completion.set(executor.submit(
					() -> outboxEventRepository.markPublished(eventId, "relay-a", NOW)
				));
				try {
					return completion.get().get(1, java.util.concurrent.TimeUnit.SECONDS);
				} catch (java.util.concurrent.TimeoutException exception) {
					return false;
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("completion wait was interrupted", exception);
				} catch (ExecutionException exception) {
					throw new AssertionError("completion task failed", exception.getCause());
				}
			});

			assertThat(completedBeforeOuterTransaction).isTrue();
			assertThat(completion.get().get()).isTrue();
		} finally {
			executor.shutdownNow();
		}
	}
}
