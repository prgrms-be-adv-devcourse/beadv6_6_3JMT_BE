package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.service.refund.RefundReconciliationClaimService;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import com.prompthub.order.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.prompthub.order.fixture.OrderRefundFixture.paidProduct;
import static org.assertj.core.api.Assertions.assertThat;

class RefundReconciliationClaimConcurrencyTest extends PostgresIntegrationTest {

	@Autowired RefundReconciliationClaimService claimService;
	@Autowired OrderRefundPersistence refundPersistence;

	@BeforeEach
	void clean() {
		refundPersistence.deleteAll();
	}

	@Test
	void concurrentClaims_returnDisjointBatches() throws Exception {
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 14, 10, 0);
		IntStream.range(0, 4).mapToObj(index -> refund(requestedAt, index)).forEach(refundPersistence::save);
		refundPersistence.flush();
		LocalDateTime now = requestedAt.plusMinutes(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		List<List<UUID>> claimed;
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<List<UUID>> first = executor.submit(() -> claim(now, ready, start));
			Future<List<UUID>> second = executor.submit(() -> claim(now, ready, start));
			ready.await();
			start.countDown();
			claimed = List.of(first.get(), second.get());
		}

		assertThat(claimed.getFirst()).hasSize(2);
		assertThat(claimed.getLast()).hasSize(2);
		Set<UUID> intersection = new HashSet<>(claimed.getFirst());
		intersection.retainAll(claimed.getLast());
		assertThat(intersection).isEmpty();
	}

	@Test
	void expiredLease_becomesClaimableAgain() {
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 14, 10, 0);
		OrderRefund refund = refundPersistence.saveAndFlush(refund(requestedAt, 0));
		LocalDateTime now = requestedAt.plusMinutes(2);

		assertThat(claimService.claimDueRequests(now, 1, Duration.ofMinutes(1))).containsExactly(refund.getId());
		assertThat(claimService.claimDueRequests(now.plusSeconds(59), 1, Duration.ofMinutes(1))).isEmpty();
		assertThat(claimService.claimDueRequests(now.plusSeconds(61), 1, Duration.ofMinutes(1)))
			.containsExactly(refund.getId());
	}

	private List<UUID> claim(LocalDateTime now, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		return claimService.claimDueRequests(now, 2, Duration.ofMinutes(1));
	}

	private OrderRefund refund(LocalDateTime requestedAt, int index) {
		return OrderRefund.request(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			List.of(paidProduct(UUID.randomUUID(), 10_000 + index)), requestedAt
		);
	}
}
